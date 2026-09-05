(ns kuro.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.session :as sess]
            [kuro.stream :as stream]
            [kuro.terminal :as t]))

(defn- sess1 [] (t/session "s1" "cid:repo" :terminal-repo))
(defn- cmd1 [] (t/command ["npm" "test"]))

(defn- live-reg []
  (-> (sess/registry)
      (sess/spawn "build" (stream/open (sess1) (cmd1)))
      (sess/spawn "dev" (stream/open (sess1) (t/command ["npm" "run" "dev"])))
      (sess/attach "w1" "build")
      (sess/attach "w2" "dev")))

(deftest spawn-and-attach-shape
  (let [reg (live-reg)]
    (is (= {"build" :running "dev" :running} (sess/session-states reg)))
    (is (= :attached (get-in reg [:kuro.registry/attachments "w1" :kuro.attachment/state])))))

(deftest duplicate-names-are-rejected
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"already exists"
                        (sess/spawn (live-reg) "build" (stream/open (sess1) (cmd1))))))

(deftest blank-names-are-rejected
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"blank"
                        (sess/spawn (sess/registry) " " (stream/open (sess1) (cmd1))))))

(deftest spawn-requires-a-running-stream
  (let [done (stream/mark-finished (stream/open (sess1) (cmd1)))]
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #":running"
                          (sess/spawn (sess/registry) "x" done)))))

(deftest detach-does-not-touch-the-stream
  (testing "tmux semantics: the window dies, the work keeps running"
    (let [reg (-> (live-reg)
                  (sess/detach "w1"))
          s (get-in reg [:kuro.registry/sessions "build" :kuro.session/stream])]
      (is (true? (stream/running? s)))
      (is (= :detached (get-in reg [:kuro.registry/attachments "w1" :kuro.attachment/state])))
      ;; detached でも出力は溜まり続ける
      (let [reg2 (update-in reg [:kuro.registry/sessions "build" :kuro.session/stream]
                            stream/append-chunk {:stream :stdout :text "still going\n"})]
        (is (= ["still going\n"]
               (map :text (second (sess/read-out reg2 "w1")))))))))

(deftest read-advances-the-cursor-exactly-once
  (let [reg (-> (live-reg)
                (update-in [:kuro.registry/sessions "build" :kuro.session/stream]
                           stream/append-chunk {:stream :stdout :text "a\n"})
                (update-in [:kuro.registry/sessions "build" :kuro.session/stream]
                           stream/append-chunk {:stream :stdout :text "b\n"}))
        [a1 out1] (sess/read-out reg "w1")]
    (is (= ["a\n" "b\n"] (map :text out1)))
    (is (= 1 (:kuro.attachment/seq a1)))
    ;; cursor を進めた attachment で再度読むと空 — 二重配信しない
    (let [reg2 (assoc-in reg [:kuro.registry/attachments "w1"] a1)
          [_ out2] (sess/read-out reg2 "w1")]
      (is (empty? out2)))))

(deftest read-fails-on-dangling-attachment
  (testing "attachment が指す session が無い読みは静かに空を返さない"
    (let [reg (-> (sess/registry)
                  (sess/spawn "x" (stream/open (sess1) (cmd1)))
                  (sess/attach "w" "x")
                  (update :kuro.registry/sessions dissoc "x"))]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"no session"
                            (sess/read-out reg "w"))))))

(deftest reattach-carries-the-session-and-reads-the-gap
  (let [reg (-> (live-reg)
                (sess/detach "w1")
                (update-in [:kuro.registry/sessions "build" :kuro.session/stream]
                           stream/append-chunk {:stream :stdout :text "while away\n"})
                (sess/reattach "w1" "w1b"))
        [_ out] (sess/read-out reg "w1b")]
    (is (= ["while away\n"] (map :text out)))
    (is (= "build" (get-in reg [:kuro.registry/attachments "w1b" :kuro.attachment/session])))
    ;; 元の窓は証跡として残る
    (is (= :detached (get-in reg [:kuro.registry/attachments "w1" :kuro.attachment/state])))))

(deftest kill-closes-and-keeps-the-receipt
  (let [reg (-> (live-reg)
                (update-in [:kuro.registry/sessions "build" :kuro.session/stream]
                           stream/append-chunk {:stream :stdout :text "ok\n"}))
        [reg2 receipt] (sess/kill reg "build" {:exit-code 0 :started-at 1 :ended-at 9})]
    (is (= 0 (:kuro/exit-code receipt)))
    (is (= "ok\n" (:kuro/stdout receipt)))
    (is (= :exited (get-in reg2 [:kuro.registry/sessions "build" :kuro.session/stream :kuro/state])))
    ;; 残った方には触れない
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"already finished"
                          (sess/kill reg2 "build" {:exit-code 0})))))

(deftest checkpoint-round-trip-is-honest
  (let [reg (-> (live-reg)
                (update-in [:kuro.registry/sessions "build" :kuro.session/stream]
                           stream/append-chunk {:stream :stdout :text "compiled\n"}))
        back (sess/restore (sess/->edn reg))]
    (testing "stream は orphaned — 死んだプロセスを running と読ませない"
      (is (= {"build" :orphaned "dev" :orphaned} (sess/session-states back))))
    (testing "attachment は suspended — 前の窓を attached と読ませない"
      (is (= :suspended (get-in back [:kuro.registry/attachments "w1" :kuro.attachment/state])))
      (is (= :suspended (get-in back [:kuro.registry/attachments "w2" :kuro.attachment/state]))))
    (testing "出力とコマンドは保存時点のまま"
      (is (= "compiled\n"
             (stream/text-of (get-in back [:kuro.registry/sessions "build" :kuro.session/stream])
                             :stdout)))
      (is (= (cmd1)
             (get-in back [:kuro.registry/sessions "build" :kuro.session/stream :kuro/command]))))))
(deftest restore-rejects-unknown-version
  (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error)
                        #"version"
                        (sess/restore {::sess/version 99}))))
