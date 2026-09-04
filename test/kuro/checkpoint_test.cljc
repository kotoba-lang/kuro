(ns kuro.checkpoint-test
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.checkpoint :as cp]
            [kuro.stream :as stream]
            [kuro.terminal :as t]))

(defn- sess [] (t/session "s1" "cid:repo" :terminal-repo))
(defn- cmd [] (t/command ["npm" "test"]))

(defn- running-stream []
  (-> (stream/open (sess) (cmd))
      (stream/append-chunk {:stream :stdout :text "compiling…\n"})
      (stream/append-chunk {:stream :stderr :text "warn\n"})))

(deftest round-trips-what-matters
  (let [st (running-stream)
        back (cp/restore (cp/->edn st))]
    (is (= "compiling…\n" (stream/text-of back :stdout)))
    (is (= "warn\n" (stream/text-of back :stderr)))
    (is (= (:kuro/stdout-bytes st) (:kuro/stdout-bytes back)))
    (is (= (:kuro/seq st) (:kuro/seq back)))
    (is (= (:kuro/command st) (:kuro/command back)))
    (is (= (:kuro/session st) (:kuro/session back)))))

(deftest a-restored-run-is-never-running
  (testing "the process did not survive the host — saying :running invites stdin to a corpse"
    (let [back (cp/restore (cp/->edn (running-stream)))]
      (is (cp/orphaned? back))
      (is (false? (stream/running? back)))
      (is (= :running (:kuro/restored-from back)) "what it was is still recorded"))))

(deftest a-finished-run-restores-as-finished
  (let [st (stream/mark-finished (running-stream))
        back (cp/restore (cp/->edn st))]
    (is (not (cp/orphaned? back)))
    (is (= :exited (:kuro/state back)))))

(deftest an-orphan-can-be-closed-into-a-receipt
  (testing "a run that never finished must still be closable, or the ledger keeps a hole"
    (let [r (cp/abandon (cp/restore (cp/->edn (running-stream))))]
      (is (= :kuro/receipt (:kuro/type r)))
      (is (= 129 (:kuro/exit-code r)))
      (is (= "process did not survive its host" (:kuro/error r)))
      (is (= :none (:kuro/isolation r)))
      (is (= "compiling…\n" (:kuro/stdout r)) "the output it did produce is kept")))
  (testing "the host may supply what it actually knows"
    (let [r (cp/abandon (cp/restore (cp/->edn (running-stream)))
                        {:exit-code 137 :error "OOM-killed"})]
      (is (= 137 (:kuro/exit-code r)))
      (is (= "OOM-killed" (:kuro/error r))))))

(deftest abandon-refuses-a-run-that-has-a-real-ending
  ;; README: "`cp/abandon` closes an **orphan** into a receipt" — orphan のみ。
  ;; すでに終わった (:exited など) stream に 129 を捏造すると、台帳に
  ;; 「起きてもいない終わり方」が載る。
  (testing "an already-finished stream is refused, not closed as exit 129"
    (let [exited (stream/mark-finished (running-stream))]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (cp/abandon (cp/restore (cp/->edn exited)))))))
  (testing "the legit path still works: restored orphan closes into a receipt"
    (is (= 129 (:kuro/exit-code (cp/abandon (cp/restore (cp/->edn (running-stream)))))))))

(deftest truncation-is-declared-not-silent
  (let [st (-> (stream/open (sess) (cmd))
               (stream/append-chunk {:stream :stdout :text "0123456789"}))
        c (cp/->edn st {:max-chunk-bytes 4})]
    (is (= 6 (:kuro.checkpoint/dropped-bytes c)))
    (is (= "0123" (:text (first (:kuro/chunks c)))))
    (testing "byte counters keep the pre-truncation truth"
      (is (= 10 (:kuro/stdout-bytes c)))))
  (testing "no cap means no truncation and no field"
    (is (nil? (:kuro.checkpoint/dropped-bytes (cp/->edn (running-stream)))))))

(deftest cap-spans-chunks
  (let [st (-> (stream/open (sess) (cmd))
               (stream/append-chunk {:stream :stdout :text "aaaa"})
               (stream/append-chunk {:stream :stdout :text "bbbb"})
               (stream/append-chunk {:stream :stdout :text "cccc"}))
        c (cp/->edn st {:max-chunk-bytes 6})]
    (is (= ["aaaa" "bb"] (mapv :text (:kuro/chunks c))))
    (is (= 6 (:kuro.checkpoint/dropped-bytes c)))))

(deftest an-unknown-version-is-refused
  (testing "a checkpoint from a future format must not be silently misread"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (cp/restore (assoc (cp/->edn (running-stream))
                                    :kuro.checkpoint/version 999))))))

(deftest summary-says-the-operational-facts
  (let [s (cp/summary (cp/->edn (running-stream)))]
    (is (re-find #"npm test" s))
    (is (re-find #"running" s))
    (is (re-find #"\d+B" s))))
