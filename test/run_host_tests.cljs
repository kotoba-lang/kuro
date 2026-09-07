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
            [kuro.host.stream-browser-test]
            [kuro.host.stream-node-test]
            [kuro.host.supervisor-test]))

;; ---------------------------------------------------------------------------
;; Registration guard (issue #99).
;;
;; nbb's SCI analyzer (1.4.208, the version this repo pins) can SILENTLY fail
;; to compile some `deftest` forms: the var is interned but bound to a
;; placeholder with NO `:test` meta, so `cljs.test` never runs it and the
;; suite reports green anyway. Measured: `kuro.host.stream-node-test` is
;; 4/29 — 25 tests (including the #96/#97/#98 guards) loaded but never ran,
;; and CI has been green the whole time. This is the exact failure class
;; CLAUDE.md warns about ("緑だった側は誰も使わず"). `cljs.test` has no way to
;; know a deftest was dropped, so the runner asserts the registered `:test`
;; deftest count equals the source count and exits nonzero on a gap.
;;
;; Root cause (Sept 2026): a stray paren in the stream-node test file nested
;; deftests #3-#27 inside deftest #2, so they were never top-level forms - NOT
;; an nbb version bug (the drop persists across nbb 1.4.208 and 1.5.212). This
;; guard is the permanent watchdog that turns any silent test-drop into a hard,
;; named CI failure regardless of cause.
(def deftest-count
  {'kuro.host.limits-test 4
   'kuro.host.node-test 24
   'kuro.host.opfs-test 8
   'kuro.host.stream-browser-test 10
   'kuro.host.stream-node-test 29
   'kuro.host.supervisor-test 9})

(defn- registered-deftests
  [ns-sym]
  (count (filter (fn [[_ v]] (:test (meta v)))
                 (ns-interns (find-ns ns-sym)))))

(defn- assert-deftests-registered!
  []
  (doseq [[ns-sym expected] deftest-count]
    (let [registered (registered-deftests ns-sym)]
      (when (not= expected registered)
        (println "TEST-REGISTRATION-GAP" (str ns-sym)
                 "expected" expected "deftests but nbb registered" registered
                 "- a deftest silently failed to compile; the suite below is NOT"
                 "exercising the code it claims to. Fix the nbb SCI drop before"
                 "trusting this gate.")
        (set! (.-exitCode js/process) 1)))))

(assert-deftests-registered!)

(defmethod test/report [::test/default :end-run-tests] [m]
  (when-not (test/successful? m)
    (set! (.-exitCode js/process) 1)))

(test/run-tests 'kuro.host.limits-test 'kuro.host.node-test 'kuro.host.opfs-test 'kuro.host.stream-browser-test 'kuro.host.stream-node-test 'kuro.host.supervisor-test)