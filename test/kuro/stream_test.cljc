(ns kuro.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.stream :as stream]
            [kuro.terminal :as t]))

(defn- sess [] (t/session "s1" "cid:repo" :terminal-safe))
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
