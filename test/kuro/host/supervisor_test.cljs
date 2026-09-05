(ns kuro.host.supervisor-test
  "async テスト。cljs.test の async を使い、コールバックが呼ばれたことを必ず
  確認する (呼ばれなければタイムアウトで落ちる)。"
  (:require [cljs.test :refer [deftest is testing async]]
            [kuro.host.supervisor :as sup]
            [kuro.terminal :as t]))

(def node (.-execPath js/process))

(defn- safe [] (t/session "s1" "repo-cid" :terminal-repo))
(defn- emit [src] (t/command [node "-e" src]))

(defn- verify-run-output [s]
  (testing "a finished session stays in the registry, marked exited"
    (is (= {"build" :exited} (sup/session-states s))))
  (testing "attachments can read what streamed in"
    (is (= ["one\n" "two\n"] (map :text (sup/read-window s "build")))))
  (testing "receipt is captured on exit"
    (is (= 0 (:kuro/exit-code (sup/receipt-of s "build"))))))

(deftest start-registers-and-streams-into-registry
  (async done
    (let [s (sup/new-supervisor)]
      (sup/start! s "build" (safe)
                  (emit "process.stdout.write('one\\n'); setTimeout(()=>process.stdout.write('two\\n'),40)")
                  {:repo-root "."
                   :on-exit (fn [_] (verify-run-output s) (done))})
      (is (= {"build" :running} (sup/session-states s))
          "registered synchronously at start!"))))

(defn- verify-after-gap [s done]
  (is (= ["before" "after"] (map :text (sup/read-window s "build")))
      "detached window reads the accumulated output")
  (sup/reattach-window! s "build" "w2")
  (testing "reattached window inherits the read position"
    (is (empty? (sup/read-window s "w2")) "no double-delivery across reattach"))
  (done))

(deftest detach-keeps-the-child-running-and-reads-the-gap
  (async done
    (let [s (sup/new-supervisor)
          seen (atom [])]
      (sup/start! s "build" (safe)
                  (emit "process.stdout.write('before'); setTimeout(()=>process.stdout.write('after'),15); setTimeout(()=>process.exit(0),120)")
                  {:repo-root "."
                   :on-chunk (fn [_ c]
                               (swap! seen conj (:text c))
                               (when (= 2 (count @seen))
                                 ;; both chunks observed via on-chunk — now verify
                                 (verify-after-gap s done)))})
      ;; detach the window at start; the child keeps producing into the registry
      (sup/detach-window! s "build"))))

(defn- check-restore [sup s2]
  (sup/restore! s2 (sup/snapshot sup))
  (testing "restored stream is orphaned — honest about death"
    (is (= {"build" :orphaned} (sup/session-states s2))))
  (testing "restored attachment is suspended — not pretending to be live"
    (is (= :suspended
           (get-in (sup/registry s2)
                   [:kuro.registry/attachments "build" :kuro.attachment/state])))))

(deftest snapshot-restore-is-honest
  (testing "checkpoint a running command, then restore in a fresh supervisor —
            the dead process is reported honestly as orphaned"
    (async done
      (let [s (sup/new-supervisor)
            s2 (sup/new-supervisor)
            once (atom false)]
        (sup/start! s "build" (safe)
                    (emit "process.stdout.write('x'); setInterval(()=>{},1000)")
                    {:repo-root "."
                     :on-chunk (fn [_ _]
                                 (when-not @once
                                   (reset! once true)
                                   (check-restore s s2)
                                   (sup/kill! s "build")
                                   (done)))})))))

(deftest snapshot-mid-run-freezes-a-prefix-the-live-run-keeps-going
  (testing "snapshot taken between chunks: the restored registry is frozen at
            the prefix received so far (no loss, no duplication), while the
            live registry keeps receiving and finishes with a receipt.
            sup/start!'s chunk wrapper updates the registry copy BEFORE the
            caller's on-chunk runs, so a snapshot taken inside on-chunk must
            already contain that chunk."
    (async done
      (let [live (sup/new-supervisor)
            s2 (sup/new-supervisor)
            seen (atom 0)]
        (sup/start! live "build" (safe)
                    (emit "process.stdout.write('a\\n'); setTimeout(()=>process.stdout.write('b\\n'),30); setTimeout(()=>process.exit(0),90)")
                    {:repo-root "."
                     :on-chunk (fn [_ _]
                                 (when (= 1 (swap! seen inc))
                                   (sup/restore! s2 (sup/snapshot live))))
                     :on-exit (fn [r]
                                (is (= 0 (:kuro/exit-code r)))
                                (testing "live run finishes normally"
                                  (is (= {"build" :exited} (sup/session-states live)))
                                  (is (= "a\nb\n" (:kuro/stdout (sup/receipt-of live "build")))))
                                (testing "restored copy is frozen at the prefix"
                                  (is (= {"build" :orphaned} (sup/session-states s2)))
                                  (is (nil? (sup/receipt-of s2 "build"))
                                      "the restored host never ran the process")
                                  (is (= ["a\n"] (map :text (sup/read-window s2 "build"))))
                                  (is (empty? (sup/read-window s2 "build"))
                                      "the later chunk and exit never leak into the frozen copy"))
                                (done))})))))

(deftest kill-on-a-restored-session-closes-without-a-receipt
  (testing "restored (handle-less) session: kill! marks the stream finished and
            stores no receipt — a dead process has no host-measured exit values,
            so no receipt is invented (sup/kill! docstring)"
    (async done
      (let [live (sup/new-supervisor)
            s2 (sup/new-supervisor)
            once (atom false)]
        (sup/start! live "build" (safe)
                    (emit "process.stdout.write('x'); setInterval(()=>{},1000)")
                    {:repo-root "."
                     :on-chunk (fn [_ _]
                                 (when-not @once
                                   (reset! once true)
                                   (sup/restore! s2 (sup/snapshot live))
                                   (is (= {"build" :orphaned} (sup/session-states s2)))
                                   (sup/kill! s2 "build")
                                   (is (= {"build" :exited} (sup/session-states s2))
                                       "kill! on a restored session marks it finished")
                                   (is (nil? (sup/receipt-of s2 "build"))
                                       "no receipt is fabricated for a process this host never ran")
                                   (sup/kill! live "build")
                                   (done)))})))))

(deftest denial-registers-nothing
  (let [s (sup/new-supervisor)
        out (sup/start! s "x"
                        (t/session "s1" "repo-c" :terminal-repo
                                   {:kuro/grant {:capabilities #{}}})
                        (emit "process.stdout.write('X')")
                        {:repo-root "."})]
    (is (false? (:kuro/allowed? out)))
    (is (= {} (sup/session-states s)) "no session registered on denial")
    (is (nil? (sup/handle-of s "x")) "no child was created")))