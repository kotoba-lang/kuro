(ns kuro.terminal-test
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.terminal :as t]))

(deftest builds-safe-session-and-receipt
  (let [sess (t/session "s1" "cid:repo" :terminal-repo)
        cmd (t/command ["clojure" "-M:test"])
        rcpt (t/receipt sess cmd {:exit-code 0 :stdout "ok\n" :stderr ""})]
    (is (= :terminal-repo (:kuro/mode sess)))
    (is (t/command-allowed? sess ["repo/read"]))
    (is (= ["clojure" "-M:test"] (:kuro/argv rcpt)))
    (is (= 0 (:kuro/exit-code rcpt)))
    (is (= :kuro/terminal-receipt (:kotoba/type (t/receipt-fact rcpt))))))

(deftest command-argv-is-a-plain-vector-of-non-blank-strings
  (testing "README enforce row 'no shell interpolation' starts here: the model
            itself only accepts an argv vector, so a single command *string* —
            the shape a shell would take — is refused before any host sees it"
    (is (= {:kuro/type :kuro/command :kuro/argv ["true" "-x"]}
           (select-keys (t/command ["true" "-x"]) [:kuro/type :kuro/argv])))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (t/command "true && rm -rf /"))))
  (testing "empty argv is refused — there is nothing to run, and 'whatever the
            host defaults to' is not a decision the model makes"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (t/command []))))
  (testing "blank elements are refused — a blank argv element is either a
            quoting bug or an injection attempt; neither may reach spawn"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (t/command ["echo" ""])))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (t/command ["echo" "  "])))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (t/command ["echo" nil])))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (t/command ["echo" 42])))))

(deftest receipt-is-one-namespace
  (testing "host-supplied result keys land in :kuro/*, not bare"
    (let [rcpt (t/receipt (t/session "s1" "cid:repo" :terminal-repo)
                          (t/command ["true"])
                          {:exit-code 0 :stdout "ok" :stdout-cid "bafkrei…"
                           :duration-ms 12 :timed-out? false})]
      (is (= "ok" (:kuro/stdout rcpt)))
      (is (= "bafkrei…" (:kuro/stdout-cid rcpt)))
      (is (= 12 (:kuro/duration-ms rcpt)))
      (is (false? (:kuro/timed-out? rcpt)))
      (is (nil? (:stdout rcpt)) "the bare key is gone")
      (is (every? #(= "kuro" (namespace %)) (remove #{:kotoba/type} (keys rcpt)))))))

(deftest receipt-drops-undeclared-result-keys
  (testing "a host cannot widen the receipt shape by adding keys"
    (let [rcpt (t/receipt (t/session "s1" "cid:repo" :terminal-repo)
                          (t/command ["true"])
                          {:exit-code 0 :secret-token "leak" :pid 4242})]
      (is (nil? (:kuro/secret-token rcpt)))
      (is (nil? (:kuro/pid rcpt))))))

(deftest receipt-requires-integer-exit-code
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (t/receipt (t/session "s1" "cid:repo" :terminal-repo)
                          (t/command ["true"])
                          {:exit-code nil}))))

(deftest denies-missing-capabilities
  (let [sess (t/session "s1" "cid:repo" :terminal-repo)]
    (is (not (t/command-allowed? sess ["secrets/get"])))
    (is (= {:kuro/allowed? false
            :kuro/reason :missing-capabilities
            :kuro/missing ["secrets/get"]
            :kuro/session-id "s1"}
           (t/denial sess ["secrets/get"])))))

(deftest host-terminal-requires-signed-opt-in
  (testing "unsigned host mode is rejected"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (t/session "host" "cid:repo" :terminal-host))))
  (testing "signed host mode is allowed"
    (is (= :terminal-host
           (:kuro/mode (t/session "host" "cid:repo" :terminal-host
                                  {:kuro/signed-opt-in? true}))))))

(deftest every-receipt-states-what-enforced-it
  (testing "no isolation is a recorded fact, not an omission"
    ;; 隔離の有無を書かない receipt は、隔離されていたかのように読まれる。
    ;; この repo の backing は fs も network も confine しないので、
    ;; そう書いてある receipt が出る。
    (let [r (t/receipt (t/session "s1" "cid:repo" :terminal-repo)
                       (t/command ["true"]) {:exit-code 0})]
      (is (= :none (:kuro/isolation r)))))
  (testing "a host that really confines says so, and it survives into the receipt"
    (let [r (t/receipt (t/session "s1" "cid:repo" :terminal-repo)
                       (t/command ["true"]) {:exit-code 0 :isolation :microvm})]
      (is (= :microvm (:kuro/isolation r))))))

(deftest declared-modes-are-grant-scopes-with-defaults
  ;; README の mode 表の全行 (:terminal-repo / :terminal-build / :terminal-agent
  ;; / :terminal-host) が実装と一致すること。表に書いてあるのに model に無い
  ;; grant は、読む側が存在しない権限を仮定してしまう。
  (testing "every mode in the table exists and names its scope, not safety"
    (is (= #{:terminal-repo :terminal-build :terminal-agent :terminal-host}
           (set (keys t/terminal-modes)))))
  (testing "labels name the scope; none claims safety"
    (is (= {"repo"   :terminal-repo
            "build"  :terminal-build
            "agent"  :terminal-agent
            "host"   :terminal-host}
           (into {} (map (juxt :kuro/label :kuro/mode) (vals t/terminal-modes)))))) 
  (testing ":terminal-build is repo write + cache + bounded net, not isolation"
    (let [caps (:kuro/default-capabilities (t/terminal-modes :terminal-build))]
      (is (contains? caps "repo/write"))
      (is (contains? caps "net/fetch"))
      (is (false? (:kuro/host? (t/terminal-modes :terminal-build))))))
  (testing ":terminal-agent carries the checkpoint capability"
    (let [caps (:kuro/default-capabilities (t/terminal-modes :terminal-agent))]
      (is (contains? caps "agent/checkpoint"))
      (is (not (contains? caps "repo/write")))))
  (testing "sessions in these modes get the declared defaults as their grant"
    (is (contains? (t/effective-capabilities
                     (t/session "b1" "cid:repo" :terminal-build))
                   "cache/write"))
    (is (contains? (t/effective-capabilities
                     (t/session "a1" "cid:repo" :terminal-agent))
                   "agent/checkpoint"))))

(deftest mode-name-does-not-claim-safety
  (testing "the default mode is named for its grant scope, not for isolation"
    (is (contains? t/terminal-modes :terminal-repo))
    (is (not (contains? t/terminal-modes :terminal-safe)))
    (is (= "repo" (:kuro/label (t/terminal-modes :terminal-repo))))))
