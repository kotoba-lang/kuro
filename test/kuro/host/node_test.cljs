(ns kuro.host.node-test
  "Every claim in `kuro.host.node`'s guarantee table gets a test that would
  fail if the guarantee were dropped. Claims the provider does *not* make
  (filesystem confinement, network) get no test — an assertion that passes
  because nothing tried to violate it is theater."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
            [kuro.host.cid :as cid]
            [kuro.host.node :as host]
            [kuro.terminal :as t]))

;; Absolute path to the running node binary. Tests must not depend on the
;; provider's PATH: the point of `default-env` is that PATH is declared, and a
;; test that needs a specific PATH cannot also prove that.
(def node (.-execPath js/process))

(defn- safe-session
  ([] (safe-session {}))
  ([attrs] (t/session "test" "repo-cid" :terminal-repo attrs)))

(defn- emit [js-src] (t/command [node "-e" js-src]))

;; ---------------------------------------------------------------- cid

(deftest cid-matches-published-vector
  (testing "CIDv1/raw/sha2-256 of \"hello world\" is the vector IPFS publishes"
    (is (= "bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e"
           (cid/text-cid "hello world")))))

(deftest cid-shape
  (testing "the 0x01 0x55 0x12 0x20 header always renders as bafkrei…"
    (doseq [s ["" "ok\n" "a longer body with ünïcode"]]
      (let [c (cid/text-cid s)]
        (is (str/starts-with? c "bafkrei"))
        ;; 'b' + base32(36 bytes) = 1 + ceil(36*8/5) = 59
        (is (= 59 (count c)))))))

(deftest cid-is-content-addressed
  (is (= (cid/text-cid "same") (cid/text-cid "same")))
  (is (not= (cid/text-cid "a") (cid/text-cid "b"))))

;; ------------------------------------------------------------- confine

(deftest confine-allows-inside
  (is (some? (host/confine "/tmp/repo" ".")))
  (is (some? (host/confine "/tmp/repo" "src")))
  (is (some? (host/confine "/tmp/repo" "src/../test"))))

(deftest confine-rejects-escape
  (testing "resolved, not spotted — the check is on the result, not on '..'"
    (is (nil? (host/confine "/tmp/repo" "..")))
    (is (nil? (host/confine "/tmp/repo" "src/../../etc")))
    (is (nil? (host/confine "/tmp/repo" "/etc")))
    (testing "a sibling that merely shares a name prefix is outside"
      (is (nil? (host/confine "/tmp/repo" "/tmp/repo-evil"))))))

(deftest cwd-escape-throws
  (is (thrown? ExceptionInfo
               (host/run (safe-session {:kuro/cwd ".."}) (emit "0")
                         {:repo-root "/tmp/repo"}))))

;; -------------------------------------------------------- capabilities

(deftest running-anything-requires-repo-read
  (is (= #{"repo/read"} (host/required-capabilities (emit "0"))))
  (is (= #{"repo/read" "net/fetch"}
         (host/required-capabilities (assoc (emit "0") :kuro/requires #{"net/fetch"})))))

(deftest denial-happens-before-execution
  (testing "a session with an emptied grant cannot execute at all"
    (let [sess (safe-session {:kuro/grant {:capabilities #{}}})
          out (host/run sess (emit "process.stdout.write('SHOULD-NOT-RUN')")
                        {:repo-root "."})]
      (is (false? (:kuro/allowed? out)))
      (is (= :missing-capabilities (:kuro/reason out)))
      (is (= ["repo/read"] (:kuro/missing out)))
      (testing "a denial is not a receipt — no exit code, no output"
        (is (nil? (:kuro/exit-code out)))
        (is (nil? (:kuro/stdout out)))))))

(deftest declared-capability-is-checked
  (let [sess (safe-session)                       ; safe grant has no net/fetch
        cmd (assoc (emit "0") :kuro/requires #{"net/fetch"})
        out (host/run sess cmd {:repo-root "."})]
    (is (= ["net/fetch"] (:kuro/missing out)))))

(deftest terminal-host-has-no-backing
  (is (thrown? ExceptionInfo
               (host/run (t/session "h" "repo-cid" :terminal-host
                                    {:kuro/signed-opt-in? true})
                         (emit "0") {:repo-root "."}))))

;; ------------------------------------------------------------ execution

(deftest runs-and-receipts
  (let [r (host/run (safe-session) (emit "process.stdout.write('ok')")
                    {:repo-root "."})]
    (is (= 0 (:kuro/exit-code r)))
    (is (= "ok" (:kuro/stdout r)))
    (is (= :kuro/receipt (:kuro/type r)))
    (is (= :terminal-repo (:kuro/mode r)))
    (is (= #{"repo/read" "tmp/write" "log/write"} (:kuro/effective-capabilities r)))
    (testing "output is content-addressed, not just carried"
      (is (= (cid/text-cid "ok") (:kuro/stdout-cid r)))
      (is (= 2 (:kuro/stdout-bytes r))))))

(deftest nonzero-exit-is-recorded-not-thrown
  (let [r (host/run (safe-session) (emit "process.exit(3)") {:repo-root "."})]
    (is (= 3 (:kuro/exit-code r)))
    (is (nil? (:kuro/error r)))))

(deftest stderr-is-separate
  (let [r (host/run (safe-session) (emit "process.stderr.write('boom')")
                    {:repo-root "."})]
    (is (= "" (:kuro/stdout r)))
    (is (= "boom" (:kuro/stderr r)))
    (is (= (cid/text-cid "boom") (:kuro/stderr-cid r)))))

(deftest environment-is-declared-not-inherited
  (testing "a variable set in the host process does not reach the child"
    (aset (.-env js/process) "KURO_HOST_MARKER" "leaked")
    (let [r (host/run (safe-session)
                      (emit "process.stdout.write(String(process.env.KURO_HOST_MARKER))")
                      {:repo-root "."})]
      (is (= "undefined" (:kuro/stdout r)))))
  (testing "the child's environment is the declared manifest plus only what the OS injects"
    ;; macOS CoreFoundation adds __CF_USER_TEXT_ENCODING to every child process
    ;; below the spawn API — measured 2026-08-03, not something this provider
    ;; can decline. Naming it keeps the assertion exact: anything else appearing
    ;; here is a leak and fails.
    (let [r (host/run (safe-session)
                      (emit "process.stdout.write(Object.keys(process.env).sort().join(','))")
                      {:repo-root "."})
          keys (set (str/split (:kuro/stdout r) #","))]
      (is (= #{"LANG" "PATH" "TERM"} (set/intersection keys #{"LANG" "PATH" "TERM"})))
      (is (empty? (set/difference keys #{"LANG" "PATH" "TERM" "__CF_USER_TEXT_ENCODING"}))))
    (testing "TERM=dumb, because a pipe is not a terminal — same guarantee as the streaming provider"
      (let [r (host/run (safe-session)
                        (emit "process.stdout.write(process.env.TERM + ':' + process.stdout.isTTY)")
                        {:repo-root "."})]
        (is (= "dumb:undefined" (:kuro/stdout r))
            "we must not claim xterm over a pipe")))))

(deftest no-shell-interpolation
  (testing "argv reaches the binary verbatim — $HOME is a literal, not expanded"
    (let [r (host/run (safe-session) (t/command ["/bin/echo" "$HOME" "&&" "whoami"])
                      {:repo-root "."})]
      (is (= 0 (:kuro/exit-code r)))
      (is (= "$HOME && whoami" (str/trim (:kuro/stdout r)))))))

(deftest missing-binary-is-a-receipt
  (let [r (host/run (safe-session) (t/command ["kuro-no-such-binary"]) {:repo-root "."})]
    (is (= 127 (:kuro/exit-code r)))
    (is (str/includes? (:kuro/error r) "kuro-no-such-binary"))))

(deftest deadline-kills-and-says-so
  (let [r (host/run (safe-session) (emit "while (true) {}")
                    {:repo-root "." :timeout-ms 300})]
    (is (= 124 (:kuro/exit-code r)))
    (is (true? (:kuro/timed-out? r)))))

(deftest output-cap-kills-and-says-so
  (let [r (host/run (safe-session)
                    (emit "for(;;) process.stdout.write('x'.repeat(4096))")
                    {:repo-root "." :max-output-bytes 4096 :timeout-ms 10000})]
    (is (= 125 (:kuro/exit-code r)))
    (is (true? (:kuro/truncated? r)))))

(deftest clock-is-injectable
  (let [ticks (atom [100 350])
        r (host/run (safe-session) (emit "0")
                    {:repo-root "." :now #(let [[t & more] @ticks]
                                            (reset! ticks (or more [t]))
                                            t)})]
    (is (= 100 (:kuro/started-at r)))
    (is (= 350 (:kuro/finished-at r)))
    (is (= 250 (:kuro/duration-ms r)))))

(deftest receipt-becomes-a-kotoba-fact
  (let [r (host/run (safe-session) (emit "0") {:repo-root "."})
        f (t/receipt-fact r)]
    (is (= :kuro/terminal-receipt (:kotoba/type f)))
    (is (= [:kuro/receipt "test" [node "-e" "0"]] (:kotoba/id f)))))
