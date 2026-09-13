(ns hive-olympus-vim.manifest-test
  "The shipped manifest through the real mounter against a stub hive.olympus
   (a presenter seat) and a stub hive.vim (a host exposing both
   :vessel/target and :vessel/dispatch!, as hive.vim does), then against the
   real hive.olympus core manifest."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]))

(defrecord StubAddon [id hook-map]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _] {:success? true})
  (shutdown! [_] nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok})
  (excluded-tools [_] #{})
  (hooks [_] hook-map))

(def seat (atom {}))
(def dispatched (atom []))
(def target-reads (atom 0))

(def panel
  {:op :ui/show-panel :panel/id "olympus/tab-1"
   :doc {:doc/title "Olympus  tab 1/1  (0 agents: 0 working, 0 blocked, 0 error, 0 idle)"
         :doc/blocks [{:block/type :para :text "No active agents" :tone :muted}]}})

(defn olympus-stub-ctor [_]
  (->StubAddon "hive.olympus"
               {:olympus/register-presenter! (fn [id target] (swap! seat assoc id target) (target [panel]) id)
                :olympus/unregister-presenter! (fn [id] (swap! seat dissoc id) id)}))

(def vim-stub-hooks
  {:vessel/target (fn [] (swap! target-reads inc) {:vessel/id :vim :vessel/dialect :vim-channel})
   :vessel/dispatch! (fn [ops] (swap! dispatched conj ops) {:ok {:plan/ops ops}})})

(defn vim-stub-ctor [_]
  (->StubAddon "hive.vim" vim-stub-hooks))

(defn no-agents [] [])

(defn six-agents []
  (mapv (fn [i] {:agent/id (str "a-" i) :agent/name (str "a-" i)
                 :agent/status (nth [:working :blocked :error :idle] (mod i 4))})
        (range 1 7)))

(defn- manifest [file]
  (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string))

(def vim-stub-spec
  {:addon/id "hive.vim" :addon/type :native
   :addon/init-ns "hive-olympus-vim.manifest-test" :addon/init-fn "vim-stub-ctor"
   :addon/capabilities #{:vessel}})

(defn- core-spec [roster-sym]
  (update (manifest "hive-olympus.edn") :addon/config assoc
          :olympus/refresh-ms 0
          :olympus/roster-fn roster-sym))

(defn- mount-all [specs]
  (let [host (mount/atom-mount-host)
        report (mount/mount! (mount/solve specs) host)]
    [host report]))

(defn- shutdown-all! [host ids]
  (doseq [id ids]
    (when-let [a (mount-port/registered host id)]
      (try (addon/shutdown! a) (catch Throwable _ nil)))))

(deftest the-manifest-is-data-only
  (let [spec (manifest "hive-olympus-vim.edn")]
    (is (= "hive.olympus.vim" (:addon/id spec)))
    (is (= "hive-olympus.harness" (:addon/init-ns spec)))
    (is (= "addon-ctor" (:addon/init-fn spec)))
    (is (= {:olympus/host "hive.vim"} (:addon/config spec)))
    (is (= #{"hive.olympus" "hive.vim"} (:addon/dependencies spec)))
    (is (= :foss (:addon/trust-class spec)))
    (is (some #(= "hive.olympus.vim" (:addon/id %)) (:specs (mount/discover-specs))))))

(deftest the-stub-host-is-faithful-to-hive-vim
  (is (set/subset? #{:vessel/target :vessel/dispatch!} (set (keys vim-stub-hooks))))
  (if-let [real (try (requiring-resolve 'hive-vim.addon/hook-keys) (catch Throwable _ nil))]
    (is (set/subset? (set (keys vim-stub-hooks)) @real)
        "every hook the stub offers is one the real hive.vim declares")
    (println "SKIP stub fidelity against hive-vim: hive-vim not on the classpath")))

(deftest mounts-against-stubs-and-delivers-through-host-dispatch
  (reset! seat {})
  (reset! dispatched [])
  (reset! target-reads 0)
  (let [[host report] (mount-all [(manifest "hive-olympus-vim.edn")
                                  {:addon/id "hive.olympus" :addon/type :native
                                   :addon/init-ns "hive-olympus-vim.manifest-test"
                                   :addon/init-fn "olympus-stub-ctor" :addon/capabilities #{}}
                                  vim-stub-spec])
        brick (mount-port/registered host "hive.olympus.vim")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (= "hive.olympus.vim" (last (:order report))))
      (is (contains? @seat "hive.vim") "registered under the host id")
      (is (= [[panel]] @dispatched) "the seat's ops reach hive.vim's dispatch verbatim")
      (is (zero? @target-reads) "dispatch outranks target, so the target hook is never read")
      (is (= :host-dispatch (get-in (addon/health brick) [:details :route])))
      (is (empty? (:errors (mount/teardown! host (:order report)))))
      (is (empty? @seat) "teardown unregisters the presenter")
      (finally (when brick (addon/shutdown! brick))))))

(deftest mounts-against-the-real-core
  (reset! dispatched [])
  (let [[host report] (mount-all [(manifest "hive-olympus-vim.edn")
                                  (core-spec 'hive-olympus-vim.manifest-test/no-agents)
                                  vim-stub-spec])
        core (mount-port/registered host "hive.olympus")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (testing "the real core renders the empty grid and the brick delivers it"
        (is (= [[panel]] @dispatched))
        (is (= {:status :live :deliveries 1}
               (get-in (addon/health core) [:details :presenters "hive.vim"]))))
      (mount/teardown! host (:order report))
      (finally (shutdown-all! host ["hive.olympus.vim" "hive.olympus"])))))

(deftest six-agents-reach-vim-as-two-tab-panels
  (reset! dispatched [])
  (let [[host report] (mount-all [(manifest "hive-olympus-vim.edn")
                                  (core-spec 'hive-olympus-vim.manifest-test/six-agents)
                                  vim-stub-spec])]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (let [ops (first @dispatched)]
        (is (= 1 (count @dispatched)) "one delivery carries every tab")
        (is (= [:ui/show-panel :ui/show-panel] (mapv :op ops)))
        (is (= ["olympus/tab-1" "olympus/tab-2"] (mapv :panel/id ops)))
        (is (str/starts-with? (get-in (first ops) [:doc :doc/title]) "Olympus  tab 1/2"))
        (is (str/starts-with? (get-in (second ops) [:doc :doc/title]) "Olympus  tab 2/2")))
      (mount/teardown! host (:order report))
      (finally (shutdown-all! host ["hive.olympus.vim" "hive.olympus"])))))
