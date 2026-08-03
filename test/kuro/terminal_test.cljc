(ns kuro.terminal-test
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.terminal :as t]))

(deftest builds-safe-session-and-receipt
  (let [sess (t/session "s1" "cid:repo" :terminal-safe)
        cmd (t/command ["clojure" "-M:test"])
        rcpt (t/receipt sess cmd {:exit-code 0 :stdout "ok\n" :stderr ""})]
    (is (= :terminal-safe (:kuro/mode sess)))
    (is (t/command-allowed? sess ["repo/read"]))
    (is (= ["clojure" "-M:test"] (:kuro/argv rcpt)))
    (is (= 0 (:kuro/exit-code rcpt)))
    (is (= :kuro/terminal-receipt (:kotoba/type (t/receipt-fact rcpt))))))

(deftest receipt-is-one-namespace
  (testing "host-supplied result keys land in :kuro/*, not bare"
    (let [rcpt (t/receipt (t/session "s1" "cid:repo" :terminal-safe)
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
    (let [rcpt (t/receipt (t/session "s1" "cid:repo" :terminal-safe)
                          (t/command ["true"])
                          {:exit-code 0 :secret-token "leak" :pid 4242})]
      (is (nil? (:kuro/secret-token rcpt)))
      (is (nil? (:kuro/pid rcpt))))))

(deftest receipt-requires-integer-exit-code
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (t/receipt (t/session "s1" "cid:repo" :terminal-safe)
                          (t/command ["true"])
                          {:exit-code nil}))))

(deftest denies-missing-capabilities
  (let [sess (t/session "s1" "cid:repo" :terminal-safe)]
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
