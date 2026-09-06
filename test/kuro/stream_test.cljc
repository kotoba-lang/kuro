(ns kuro.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.stream :as stream]
            [kuro.terminal :as t]))

(defn- sess [] (t/session "s1" "cid:repo" :terminal-repo))
(defn- cmd [] (t/command ["echo" "hi"]))

(deftest chunks-are-ordered-by-acceptance-not-time
  (let [st (-> (stream/open (sess) (cmd))
               (stream/append-chunk {:stream :stdout :text "a"})
               (stream/append-chunk {:stream :stderr :text "E"})
               (stream/append-chunk {:stream :stdout :text "b"}))]
    (is (= [0 1 2] (mapv :kuro/seq (:kuro/chunks st))))
    (is (= "ab" (stream/text-of st :stdout)))
    (is (= "E" (stream/text-of st :stderr)))))

(deftest byte-counts-are-utf8-not-characters
  (testing "a character count would be 3x off on Japanese logs"
    (let [st (stream/append-chunk (stream/open (sess) (cmd))
                                  {:stream :stdout :text "あいう"})]
      (is (= 9 (:kuro/stdout-bytes st))))))

(deftest output-cap-drops-body-but-keeps-the-count
  (let [st (-> (stream/open (sess) (cmd) {:max-output-bytes 4})
               (stream/append-chunk {:stream :stdout :text "abcd"})
               (stream/append-chunk {:stream :stdout :text "efghij"}))]
    (is (true? (:kuro/truncated? st)))
    (is (= "abcd" (stream/text-of st :stdout)) "the over-cap chunk's body is gone")
    (is (= 6 (:kuro/dropped-bytes st)) "but its size is not")
    (is (= 4 (:kuro/stdout-bytes st)))))

(deftest cap-counts-both-streams-together
  (testing "a command that floods stderr must not bypass the cap"
    (let [st (-> (stream/open (sess) (cmd) {:max-output-bytes 4})
                 (stream/append-chunk {:stream :stdout :text "ab"})
                 (stream/append-chunk {:stream :stderr :text "cd"})
                 (stream/append-chunk {:stream :stdout :text "e"}))]
      (is (true? (:kuro/truncated? st)))
      (is (= 1 (:kuro/dropped-bytes st))))))

(deftest a-chunk-that-partly-fits-is-dropped-whole
  ;; README (stream): "kuro.stream drops chunk-wise (a chunk that does not fit
  ;; whole is dropped whole), so what is kept is always an exact prefix of the
  ;; emitted output." The two existing cap tests (output-cap-drops-body /
  ;; cap-counts-both-streams) only exercise room=0 — a chunk arriving when the
  ;; cap is already consumed. Neither pins the straddle: room > 0 but < next
  ;; chunk. If the code split the straddling chunk at the boundary, kept text
  ;; would fit under the cap yet NOT be an exact prefix of what the child
  ;; wrote, and dropped-bytes would under-count the silently discarded tail.
  ;; "Exact prefix" is the documented, stronger guarantee; pin it.
  (testing "a chunk larger than the remaining room is dropped whole, not split"
    (let [st (-> (stream/open (sess) (cmd) {:max-output-bytes 4})
                 (stream/append-chunk {:stream :stdout :text "ab"})    ; 2B, room 2
                 (stream/append-chunk {:stream :stdout :text "cdef"}))] ; 4B > room 2
      (is (true? (:kuro/truncated? st)))
      (is (= "ab" (stream/text-of st :stdout)) "the straddling chunk's body is gone entirely")
      (is (= 4 (:kuro/dropped-bytes st)) "the WHOLE chunk size is counted, not the 2 that would have fit")
      (is (= 2 (:kuro/stdout-bytes st)) "kept bytes stay the exact-prefix count"))
    (testing "a later chunk that now fits exactly arrives after the drop"
      (let [st (-> (stream/open (sess) (cmd) {:max-output-bytes 4})
                   (stream/append-chunk {:stream :stdout :text "ab"})
                   (stream/append-chunk {:stream :stdout :text "cdef"})
                   (stream/append-chunk {:stream :stdout :text "gh"}))]
        (is (= "abgh" (stream/text-of st :stdout)))))))

(deftest finish-produces-an-ordinary-receipt
  (let [r (-> (stream/open (sess) (cmd))
              (stream/append-chunk {:stream :stdout :text "hi\n"})
              (stream/finish {:exit-code 0 :duration-ms 12}))]
    (is (= :kuro/receipt (:kuro/type r)))
    (is (= "hi\n" (:kuro/stdout r)))
    (is (= 3 (:kuro/stdout-bytes r)))
    (is (= 0 (:kuro/exit-code r)))
    (is (= 12 (:kuro/duration-ms r)))
    (is (nil? (:kuro/truncated? r)))))

(deftest truncation-reaches-the-receipt
  (testing "a silently-cut receipt is indistinguishable from a short success"
    (let [r (-> (stream/open (sess) (cmd) {:max-output-bytes 2})
                (stream/append-chunk {:stream :stdout :text "abcdef"})
                (stream/finish {:exit-code 0}))]
      (is (true? (:kuro/truncated? r)))
      (is (= 6 (:kuro/dropped-bytes r))))))

(deftest a-finished-stream-refuses-more
  (let [st (stream/open (sess) (cmd))]
    (stream/finish st {:exit-code 0})
    (let [done (stream/mark-finished st)]
      (is (false? (stream/running? done)))
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (stream/append-chunk done {:stream :stdout :text "late"})))
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (stream/finish done {:exit-code 0}))))))

(deftest unknown-stream-is-rejected
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (stream/append-chunk (stream/open (sess) (cmd))
                                    {:stream :stdlog :text "x"}))))

(deftest total-bytes-sums-both-streams
  ;; kuro.stream's one public fn with no test anywhere: total-bytes. It is
  ;; what append-chunk's cap check reads on every chunk (the room is computed
  ;; from it: `(- max-output-bytes (total-bytes st))`), so it is the value
  ;; that decides where the cap fires. If it regressed (dropped the stderr
  ;; side, or double-counted), the enforce row "bounded output" would still
  ;; have green tests — stream_test only asserts via stdout/stderr-bytes
  ;; separately or via text. Pin the sum itself, in UTF-8 bytes (character
  ;; counts would be 3x off on Japanese logs — same trap as byte-counts).
  (testing "acceptance is ordered: stdout and stderr accumulate into one sum"
    (let [st (-> (stream/open (sess) (cmd))
                 (stream/append-chunk {:stream :stdout :text "ab"})
                 (stream/append-chunk {:stream :stderr :text "cd"})
                 (stream/append-chunk {:stream :stdout :text "あ"}))]
      (is (= 7 (stream/total-bytes st)) "2 + 2 + 3 bytes (あ is U+3042, 3 UTF-8 bytes)")))
  (testing "dropped bytes are NOT part of the kept total"
    (let [st (-> (stream/open (sess) (cmd) {:max-output-bytes 4})
                 (stream/append-chunk {:stream :stdout :text "abcd"})
                 (stream/append-chunk {:stream :stdout :text "efg"}))]
      (is (= 4 (stream/total-bytes st)) "the dropped 3 stay in dropped-bytes, not the sum")
      (is (= 3 (:kuro/dropped-bytes st))))))