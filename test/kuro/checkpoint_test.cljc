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

(deftest a-truncated-orphan-closes-as-a-truncated-receipt
  ;; README (kuro.checkpoint 節) は :max-chunk-bytes について「the byte
  ;; counters keep the pre-truncation truth」と言い、kuro.stream 節は「a
  ;; silently-cut receipt is indistinguishable from a short success」と言う。
  ;; この接合を pin する: stream 層で切られた出力を持つ orphan を abandon した
  ;; 時、truncation の事実 (:kuro/truncated? と :kuro/dropped-bytes) が
  ;; checkpoint → restore → receipt まで生き残らねばならない。落ちた実行が
  ;; 「成功した短い出力」の顔をして台帳に載る regression をここで止める。
  ;; なお stream 層の cap は chunk 単位 (部分保持はしない): room を超えた
  ;; chunk は本文ごと捨てられ、捨てたバイト数だけが残る。
  (let [st (-> (stream/open (sess) (cmd) {:max-output-bytes 4})
               (stream/append-chunk {:stream :stdout :text "abcd"})
               (stream/append-chunk {:stream :stdout :text "efghij"}))
        back (cp/restore (cp/->edn st))]
    (testing "the restore itself carries the stream's truncation"
      (is (true? (:kuro/truncated? back)))
      (is (= 6 (:kuro/dropped-bytes back)))
      (is (= 4 (:kuro/stdout-bytes back))))
    (testing "and the abandoned receipt repeats it, not a clean exit 129"
      (let [r (cp/abandon back)]
        (is (= 129 (:kuro/exit-code r)))
        (is (true? (:kuro/truncated? r)))
        (is (= 6 (:kuro/dropped-bytes r)))
        (is (= "abcd" (:kuro/stdout r)) "only the kept body, never the dropped tail")))
    (testing "an untruncated orphan's receipt stays untruncated"
      (let [clean (cp/abandon (cp/restore (cp/->edn (running-stream))))]
        (is (not (contains? clean :kuro/truncated?)))
        (is (not (contains? clean :kuro/dropped-bytes)))))))

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
