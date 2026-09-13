(ns hive-olympus-vim.test-runner
  (:require [clojure.test :as test]
            [hive-olympus-vim.manifest-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'hive-olympus-vim.manifest-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
