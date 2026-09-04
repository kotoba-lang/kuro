#!/usr/bin/env nbb
;; nbb test entry for the Node host provider.
;;
;; `clojure -M:test` runs the portable `.cljc` model on the JVM; it cannot
;; load `kuro.host.*`, which is ClojureScript on Node by design. Both gates
;; run in CI — the model is proved portable, the host is proved to actually
;; spawn processes.
;;
;;   nbb --classpath src:test test/run_host_tests.cljs

(ns run-host-tests
  (:require [cljs.test :as test]
            [kuro.host.limits-test]
            [kuro.host.node-test]
            [kuro.host.opfs-test]
            [kuro.host.stream-node-test]))

(defmethod test/report [::test/default :end-run-tests] [m]
  (when-not (test/successful? m)
    (set! (.-exitCode js/process) 1)))

(test/run-tests 'kuro.host.limits-test 'kuro.host.node-test 'kuro.host.opfs-test 'kuro.host.stream-node-test)
