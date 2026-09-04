(ns kuro.host.stream-node-test
  "async テスト。`cljs.test` の `async` を使い、コールバックが呼ばれたことを
  必ず確認する —— 呼ばれなければタイムアウトで落ちる（黙って通らない）。"
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing async]]
            [kuro.host.cid :as cid]
            [kuro.host.stream-node :as sh]
            [kuro.stream :as stream]
            [kuro.terminal :as t]))

(def node (.-execPath js/process))

(defn- safe [] (t/session "s1" "repo-cid" :terminal-repo))
(defn- emit [src] (t/command [node "-e" src]))

(deftest streams-output-before-the-process-exits
  (async done
    (let [chunks (atom [])]
      (sh/start (safe)
                (emit "process.stdout.write('one\\n'); setTimeout(()=>process.stdout.write('two\\n'), 40)")
                {:repo-root "."
                 :on-chunk (fn [_ c] (swap! chunks conj (:text c)))
                 :on-exit (fn [r]
                            (is (= 0 (:kuro/exit-code r)))
                            (is (= "one\ntwo\n" (:kuro/stdout r)))
                            (testing "output arrived in pieces, not all at the end"
                              (is (= 2 (count @chunks))))
                            (done))}))))

(deftest stdin-reaches-the-child
  (async done
    (let [h (sh/start (safe)
                      (emit "let b='';process.stdin.on('data',d=>b+=d);process.stdin.on('end',()=>process.stdout.write('got:'+b))")
                      {:repo-root "."
                       :on-exit (fn [r]
                                  (is (= "got:hello\n" (:kuro/stdout r)))
                                  (done))})]
      ((:write h) "hello\n")
      ((:close-stdin h)))))

(deftest kill-stops-a-long-running-command
  (async done
    (let [h (sh/start (safe)
                      (emit "setInterval(()=>{}, 1000)")
                      {:repo-root "." :timeout-ms 10000
                       :on-exit (fn [r]
                                  (testing "the receipt says who stopped it"
                                    (is (or (str/includes? (str (:kuro/error r)) "SIGTERM")
                                            (= 143 (:kuro/exit-code r))))
                                    (done)))})]
      (js/setTimeout #((:kill h)) 30))))

(deftest deadline-kills-a-streaming-command
  (async done
    (sh/start (safe) (emit "setInterval(()=>{}, 1000)")
              {:repo-root "." :timeout-ms 150
               :on-exit (fn [r]
                          (is (= 124 (:kuro/exit-code r)))
                          (is (true? (:kuro/timed-out? r)))
                          (done))})))

(deftest output-cap-kills-the-flood-and-reports-it
  (async done
    (sh/start (safe)
              (emit "setInterval(()=>process.stdout.write('x'.repeat(8192)), 1)")
              {:repo-root "." :max-output-bytes 4096 :timeout-ms 5000
               :on-exit (fn [r]
                          (is (= 125 (:kuro/exit-code r)))
                          (is (true? (:kuro/truncated? r)))
                          (is (pos? (:kuro/dropped-bytes r)))
                          (done))})))

(deftest denial-happens-before-spawn
  (let [out (sh/start (t/session "s1" "repo-cid" :terminal-repo
                                 {:kuro/grant {:capabilities #{}}})
                      (emit "process.stdout.write('SHOULD-NOT-RUN')")
                      {:repo-root "."})]
    (is (false? (:kuro/allowed? out)))
    (is (nil? (:pid out)) "no process was created")))

(deftest terminal-host-is-refused-here-too
  (is (thrown? ExceptionInfo
               (sh/start (t/session "h" "repo-cid" :terminal-host {:kuro/signed-opt-in? true})
                         (emit "0") {:repo-root "."}))))

(deftest missing-binary-becomes-a-receipt
  (async done
    (sh/start (safe) (t/command ["kuro-no-such-binary"])
              {:repo-root "."
               :on-exit (fn [r]
                          (is (= 127 (:kuro/exit-code r)))
                          (is (str/includes? (:kuro/error r) "kuro-no-such-binary"))
                          (done))})))

(deftest no-shell-interpolation-in-the-streaming-path
  (testing "README 'The same guarantees apply — ... argv with no shell': the
            streaming provider must hand argv to the binary verbatim too —
            $HOME stays a literal, && is an argument, not a pipeline"
    (async done
      (sh/start (safe)
                (t/command ["/bin/echo" "$HOME" "&&" "whoami"])
                {:repo-root "."
                 :on-exit (fn [r]
                            (is (= "$HOME && whoami" (str/trim (:kuro/stdout r)))
                                "argv reached node verbatim; no shell in between")
                            (done))}))))

(deftest term-is-dumb-because-a-pipe-is-not-a-terminal
  (async done
    (sh/start (safe)
              (emit "process.stdout.write(process.env.TERM + ':' + process.stdout.isTTY)")
              {:repo-root "."
               :on-exit (fn [r]
                          (is (= "dumb:undefined" (:kuro/stdout r))
                              "we must not claim xterm over a pipe")
                          (done))})))

(deftest stream-environment-is-declared-not-inherited
  (testing "the same guarantee as kuro.host.node: a variable set in the host
            process does not reach a streaming child either"
    (aset (.-env js/process) "KURO_HOST_MARKER" "leaked")
    (async done
      (sh/start (safe)
                (emit "process.stdout.write(String(process.env.KURO_HOST_MARKER))")
                {:repo-root "."
                 :on-exit (fn [r]
                            (is (= "undefined" (:kuro/stdout r)))
                            (done))}))))

(deftest stream-cwd-escape-throws
  (is (thrown? ExceptionInfo
               (sh/start (t/session "s1" "repo-cid" :terminal-repo {:kuro/cwd ".."})
                         (emit "0") {:repo-root "."}))))

(deftest stream-receipt-never-omits-isolation
  (testing "README: every receipt carries :kuro/isolation, defaulting to :none —
            a receipt that omits it would be read as though it had been isolated.
            The streaming path goes through stream/finish → t/receipt, so the
            key must be there even though no host code ever names it."
    (async done
      (sh/start (safe)
                (emit "process.stdout.write('ok')")
                {:repo-root "."
                 :on-exit (fn [r]
                            (is (contains? r :kuro/isolation)
                                "omitted isolation reads as isolation")
                            (is (= :none (:kuro/isolation r)))
                            (is (map? r))
                            (is (every? #(= "kuro" (namespace %)) (keys r))
                                "receipt keys are all :kuro/*")
                            (done))}))))

(deftest run-async-resolves-a-denial-without-spawning
  (async done
    (testing "README: `run-async` resolves a denial **immediately** — the returned
            value is the denial map, not a receipt, and no process is created.
            A regression that drops the `(resolve h)` branch would leave the
            promise forever pending: the caller hangs instead of failing."
      (-> (sh/run-async (t/session "s1" "repo-cid" :terminal-repo
                                   {:kuro/grant {:capabilities #{}}})
                        (emit "process.stdout.write('SHOULD-NOT-RUN')")
                        {:repo-root "."})
          (.then (fn [out]
                   (is (false? (:kuro/allowed? out)))
                   (is (= :missing-capabilities (:kuro/reason out)))
                   (is (= ["repo/read"] (:kuro/missing out)))
                   (is (nil? (:kuro/exit-code out)) "a denial is not a receipt")
                   (is (nil? (:kuro/stdout out)))
                   (done)))))))

(deftest run-async-resolves-to-a-receipt
  (async done
    (-> (sh/run-async (safe) (emit "process.stdout.write('ok')") {:repo-root "."})
        (.then (fn [r]
                 (is (= "ok" (:kuro/stdout r)))
                 (is (= 0 (:kuro/exit-code r)))
                 (done))))))

(deftest receipt-is-content-addressed
  (testing "README 'content-addressed output' is a both-providers guarantee —
            the streaming receipt carries the same CIDv1/raw/sha2-256 as the
            sync one, not just the bytes"
    (async done
      (sh/start (safe)
                (emit "process.stdout.write('ok'); process.stderr.write('boom')")
                {:repo-root "."
                 :on-exit (fn [r]
                            (is (= (cid/text-cid "ok") (:kuro/stdout-cid r)))
                            (is (= (cid/text-cid "boom") (:kuro/stderr-cid r)))
                            (testing "the CID is of the same bytes the receipt carries"
                              (is (= "bafkrei" (subs (:kuro/stdout-cid r) 0 7)))
                              (is (= 2 (:kuro/stdout-bytes r))))
                            (done))}))))

(deftest live-state-is-observable-while-running
  (testing "the caller can read progress without waiting for exit"
    ;; 観測は wall-clock ではなく **chunk の到着**に載せる。最初の版は 25 ms の
    ;; setTimeout で覗いて落ちた（node の起動が 25 ms より遅い日があるだけ）。
    ;; 時間で同期するテストは、製品の欠陥ではなくその日のマシンの速さを測る。
    (async done
      (let [seen (atom nil)]
        (sh/start (safe)
                  (emit "process.stdout.write('a'); setTimeout(()=>process.exit(0), 60)")
                  {:repo-root "."
                   :on-chunk (fn [st _]
                               (when-not @seen
                                 (reset! seen {:running? (stream/running? st)
                                               :text (stream/text-of st :stdout)})))
                   :on-exit (fn [r]
                              (is (= {:running? true :text "a"} @seen)
                                  "state was already readable at the first chunk")
                              (is (= 0 (:kuro/exit-code r)))
                              (done))})))))

(deftest no-shell-interpolation-on-the-streaming-path-too
  (testing "README enforce row 'no shell interpolation': the guarantee belongs
            to the provider, not to one implementation. argv reaches the binary
            verbatim over cp/spawn with :shell false, exactly as in
            kuro.host.node — $HOME is a literal, && is a plain argument."
    (async done
      (-> (sh/run-async (safe)
                        (t/command [node "-e"
                                    "process.stdout.write(process.argv.slice(1).join(' '))"
                                    "$HOME" "&&" "whoami"])
                        {:repo-root "."})
          (.then (fn [r]
                   (is (= 0 (:kuro/exit-code r)))
                   (is (= "$HOME && whoami" (:kuro/stdout r))
                       "no expansion, no shell metacharacter interpretation")
                   (done)))))))
