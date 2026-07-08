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
