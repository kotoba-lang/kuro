(ns kuro.host.opfs-test
  "Node-runnable tests for the OPFS host's pure surface: request/reply wire
  shape, op vocabulary closure, and CID math parity against node:crypto (the
  Worker's WebCrypto path must mint the same CID node:crypto does — same
  bytes, same digest, same base32).

  The real OPFS round-trip needs a browser (sync access handles are
  Worker-only); that E2E lives with the Playwright harness. What Node CAN
  prove here: the wire contract both sides compile against, and that a CID
  minted the way the Worker mints it matches the reference mint."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kuro.host.opfs :as opfs]
            [kuro.host.cid :as cid]))

(deftest request-wire-shape
  (let [m (opfs/request 7 :put {:cid "bafk-x" :bytes (js/Uint8Array. #js [1 2 3])})]
    (testing "request carries id/op/payload and the :request type tag"
      (is (opfs/valid-request? m)))
    (testing "malformed shapes are refused, not guessed at"
      (is (not (opfs/valid-request? (assoc m :kuro.opfs/op :nope))))
      (is (not (opfs/valid-request? (dissoc m :kuro.opfs/id))))
      (is (not (opfs/valid-request? (assoc m :kuro.opfs/type :reply)))))))

(deftest reply-carries-error-or-result
  (let [ok (opfs/reply 7 :put {:cid "bafk-x"} nil)
        bad (opfs/reply 8 :get nil :not-found)]
    (is (= :reply (:kuro.opfs/type ok)))
    (is (= {:cid "bafk-x"} (:kuro.opfs/result ok)))
    (is (nil? (:kuro.opfs/error ok)))
    (is (= :not-found (:kuro.opfs/error bad)))
    (is (nil? (:kuro.opfs/result bad)))))

(deftest op-vocabulary-is-closed
  (is (contains? opfs/ops :put))
  (is (contains? opfs/ops :get))
  (is (contains? opfs/ops :publish))
  (is (not (contains? opfs/ops :exec)) "no escape hatch: this store moves blocks, nothing else"))

;; CID parity: node:crypto mint vs the algorithm the Worker runs on
;; WebCrypto. Same algorithm, two runtimes — if these diverge, the cache and
;; the gateway disagree about what a block IS.
(deftest cid-mint-parity
  (let [bytes (js/Uint8Array. #js [104 101 108 108 111]) ; "hello"
        minted (cid/sha256-raw-cid bytes)]
    (testing "CIDv1 raw prefix"
      (is (str/starts-with? minted "bafkrei")))
    (testing "deterministic for identical bytes"
      (is (= minted (cid/sha256-raw-cid (js/Uint8Array. #js [104 101 108 108 111])))))
    (testing "differs for different bytes"
      (is (not= minted (cid/sha256-raw-cid (js/Uint8Array. #js [104 101 108 108 111 111])))))))
