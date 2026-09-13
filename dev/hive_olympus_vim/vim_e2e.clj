(ns hive-olympus-vim.vim-e2e
  "Real-Vim check for the brick, meant for a cold JVM (never a shared hive).

   Mounts three REAL manifests through hive-addon.mount: hive.vim (its channel
   server on a temp port file), hive.olympus (a stub roster, polling) and
   hive.olympus.vim. Then starts a Vim inside a detached tmux session that
   connects to hive.vim, and reads the Olympus panel buffers back out of that
   Vim. Everything it started is torn down before it returns.

     clojure -Sdeps \"$(cat local.deps.edn)\" -M:dev -m hive-olympus-vim.vim-e2e

   REPL: (run! {:agents 6}) returns the evidence map."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [hive-vessel.doc :as doc]
            [hive-vim.addon :as vim-addon]
            [hive-vim.client :as client])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def roster-size (atom 6))

(defn stub-roster
  "N agents cycling through every status; even ones carry a task."
  [n]
  (mapv (fn [i]
          (cond-> {:agent/id (str "vim-demo-" i)
                   :agent/name (str "vim-demo-" i)
                   :agent/status (nth [:working :blocked :error :idle :spawning] (mod i 5))}
            (even? i) (assoc :agent/task (str "task " i))))
        (range 1 (inc n))))

(defn roster
  "The roster fn the core manifest names."
  []
  (stub-roster @roster-size))

(defn- manifest [file]
  (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string))

(defn- temp-dir [prefix]
  (.getCanonicalFile (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))))

(defn- delete-tree! [^java.io.File dir]
  (when (and dir (.exists dir))
    (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))

(defn wait-until
  [timeout-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 50) (recur))))))

(defn runtime-dir
  "A Vim runtime directory holding FILES found on the classpath under PREFIX:
   the classpath directory itself when it is one, else the files extracted into
   a fresh temp dir. Returns {:dir path :temp? bool}, or nil when absent."
  [prefix files]
  (when-let [url (io/resource (str prefix (first files)))]
    (if (= "file" (.getProtocol url))
      (let [path (.getCanonicalPath (io/file (.toURI url)))]
        {:dir (subs path 0 (- (count path) (count (first files)) 1)) :temp? false})
      (let [dir (temp-dir "hive-olympus-vim-rt")]
        (doseq [f files :let [out (io/file dir f)]]
          (io/make-parents out)
          (with-open [in (io/input-stream (io/resource (str prefix f)))]
            (io/copy in out)))
        {:dir (.getCanonicalPath dir) :temp? true}))))

(defn vim-command
  "The shell command tmux runs: a vanilla Vim with both plugins on its
   runtimepath, pointed at hive.vim's port file. Not headless, so each panel
   opens in a real split."
  [port-file rtps]
  (str "vim -N -u NONE -i NONE"
       (apply str (for [rtp rtps] (str " --cmd 'set rtp^=" rtp "'")))
       " --cmd 'let g:hive_port_file=\"" port-file "\"'"
       " --cmd 'let g:hive_reconnect_ms=200'"
       " -c 'runtime plugin/hive.vim'"
       " -c 'runtime plugin/hive_vessel.vim'"
       " notes.txt"))

(defn- vim-eval [server code]
  (:value (:ok (client/invoke! server {} "eval" {:code code}))))

(defn specs
  "The three real manifests, configured for an isolated run."
  [port-file refresh-ms]
  [(manifest "hive-olympus-vim.edn")
   (update (manifest "hive-olympus.edn") :addon/config assoc
           :olympus/refresh-ms refresh-ms
           :olympus/roster-fn 'hive-olympus-vim.vim-e2e/roster)
   (assoc (manifest "hive-vim.edn") :addon/config {:vim/port-file port-file})])

(defn run!
  "Mount, connect a real Vim, read the painted panels back, tear down.
   Returns the evidence map; :ok? is the verdict."
  ([] (run! {}))
  ([{:keys [agents refresh-ms timeout-ms] :or {agents 6 refresh-ms 250 timeout-ms 20000}}]
   (reset! roster-size agents)
   (let [vim-rt (runtime-dir "" ["plugin/hive.vim" "autoload/hive.vim" "autoload/hive/rpc.vim"])
         vessel-rt (runtime-dir "hive-vessel/vim/" ["plugin/hive_vessel.vim" "autoload/hive_vessel.vim"
                                                    "autoload/hive_vessel/ops.vim" "autoload/hive_vessel/wire.vim"])
         workspace (temp-dir "hive-olympus-vim-ws")
         port-file (str (io/file workspace "vim.port"))
         session (str "hive-olympus-vim-" (System/nanoTime))
         host (mount/atom-mount-host)
         report (mount/mount! (mount/solve (specs port-file refresh-ms)) host)]
     (spit (io/file workspace "notes.txt") "hive-olympus-vim e2e\n")
     (try
       (let [vim (mount-port/registered host "hive.vim")
             core (mount-port/registered host "hive.olympus")
             brick (mount-port/registered host "hive.olympus.vim")
             before-vim (get-in (addon/health core) [:details :presenters "hive.vim"])
             tmux (shell/sh "tmux" "new-session" "-d" "-s" session "-x" "200" "-y" "60"
                            "-c" (str workspace) (vim-command port-file [(:dir vim-rt) (:dir vessel-rt)]))
             server (vim-addon/server vim)
             connected? (wait-until timeout-ms #(seq (client/sessions server)))
             panels ((:olympus/panels (addon/hooks core)))
             expected (into {} (for [p panels] [(:panel/id p) (mapv :text (doc/render-lines (:doc p)))]))
             read-panel #(vim-eval server (str "hive_vessel#panel_lines('" % "')"))
             painted? (and connected?
                           (wait-until timeout-ms
                                       #(every? (fn [[id lines]] (= lines (read-panel id))) expected)))
             painted (into {} (for [id (keys expected)] [id (read-panel id)]))
             windows (into {} (for [id (keys expected)]
                                [id (vim-eval server (str "bufwinid(bufnr('hive://" id "'))"))]))
             _ (when painted? (vim-eval server "execute('redraw!')") (Thread/sleep 300))
             screen (:out (shell/sh "tmux" "capture-pane" "-p" "-t" session))
             after-vim (get-in (addon/health core) [:details :presenters "hive.vim"])
             route (get-in (addon/health brick) [:details :route])
             titles (mapv (comp first painted) (sort (keys expected)))
             ok? (boolean (and (:ok? report) (zero? (:exit tmux)) connected? painted?
                               (= ["olympus/tab-1" "olympus/tab-2"] (sort (keys painted)))
                               (str/starts-with? (str (first titles)) "Olympus  tab 1/2")
                               (str/starts-with? (str (second titles)) "Olympus  tab 2/2")
                               (every? #(not= -1 %) (vals windows))
                               (= :host-dispatch route)
                               (= :live (:status after-vim))))]
         {:ok? ok?
          :mount {:ok? (:ok? report) :order (:order report)}
          :runtime {:hive-vim vim-rt :hive-vessel vessel-rt}
          :presenter-before-vim before-vim
          :presenter-after-vim after-vim
          :route route
          :connected? (boolean connected?)
          :painted? (boolean painted?)
          :panels painted
          :panel-windows windows
          :titles titles
          :screen screen})
       (finally
         (shell/sh "tmux" "kill-session" "-t" session)
         (try (mount/teardown! host (:order report)) (catch Throwable _ nil))
         (doseq [id ["hive.olympus.vim" "hive.olympus" "hive.vim"]]
           (when-let [a (mount-port/registered host id)]
             (try (addon/shutdown! a) (catch Throwable _ nil))))
         (delete-tree! workspace)
         (doseq [rt [vim-rt vessel-rt] :when (:temp? rt)]
           (delete-tree! (io/file (:dir rt)))))))))

(defn -main
  [& _]
  (let [evidence (run!)]
    (pp/pprint (dissoc evidence :screen))
    (println "---- tmux screen ----")
    (println (:screen evidence))
    (println "tmux sessions left:" (str/trim (:out (shell/sh "sh" "-c" "tmux ls 2>/dev/null | grep -c hive-olympus-vim || true"))))
    (shutdown-agents)
    (System/exit (if (:ok? evidence) 0 1))))
