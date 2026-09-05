#!/usr/bin/env nbb
;; **同じ `.cljc` テストを ClojureScript でも回す。**
;;
;; `kuro.ansi` / `kuro.stream` は `.cljc` で「portable」と名乗っているのに、
;; 2026-08-03 まで JVM でしか実行されていなかった。その間 `kuro.ansi` は
;; ClojureScript で**出力を丸ごと落としていた**（`(int c)` が NaN になり、
;; CSI の終端バイトを見つけられず全部捨てていた）。JVM のテストは緑のまま、
;; kobo のサーバ（nbb = cljs）だけが壊れているという状態が landed していた。
;;
;; portable と書いたなら両方で回す。片方でしか回さない `.cljc` は、
;; 「動く」と言っている側が実は一度も動いていないことがある。
;;
;;   nbb --classpath src:test test/run_parity_tests.cljs

(ns run-parity-tests
  (:require [cljs.test :as test]
            [kuro.ansi-test]
            [kuro.checkpoint-test]
            [kuro.fs-test]
            [kuro.session-test]
            [kuro.stream-test]
            [kuro.terminal-test]))

(defmethod test/report [::test/default :end-run-tests] [m]
  (when-not (test/successful? m)
    (set! (.-exitCode js/process) 1)))

(test/run-tests 'kuro.ansi-test 'kuro.checkpoint-test 'kuro.fs-test 'kuro.session-test 'kuro.stream-test 'kuro.terminal-test)
