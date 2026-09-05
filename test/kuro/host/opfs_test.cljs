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

;; The vocabulary was pinned put/get/publish only, so removing :delete,
;; :stats, or :drop-cache from `opfs/ops` (or from the Worker's dispatch)
;; would have failed nothing here even though the Worker implements them and
;; the wire contract declares them. Pin the whole declared vocabulary so a
;; silently dropped op is a red test, not a main-thread hang waiting for a
;; reply the Worker no longer sends. The filesystem EFFECTS live in the
;; Worker (browser-only, sync access handles) — those stay with the E2E.
(deftest declared-vocabulary-covers-every-op-the-worker-implements
  (testing "opfs_worker.js dispatch implements exactly these six ops"
    (is (= #{:put :get :delete :publish :stats :drop-cache} opfs/ops)))
  (doseq [op [:delete :stats :drop-cache]]
    (testing (str op " is a first-class op: valid request shape, reply shape pinned")
      (let [payload (case op
                      :delete {:cid "bafk-x"}
                      :stats {}
                      :drop-cache {})]
        (is (opfs/valid-request? (opfs/request 1 op payload)))
        (let [r (opfs/reply 1 op (case op
                                   :delete {:ok true}
                                   :stats {:count 0 :bytes 0}
                                   :drop-cache {:ok true})
                            nil)]
          (is (nil? (:kuro.opfs/error r)))
          (is (map? (:kuro.opfs/result r))))))))

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

;; README (kuro.host.opfs section): "`publish` verifies each block's bytes
;; hash to its claimed CID before counting it published." The check itself
;; lives in the Worker dispatch (opfs_worker.js), which Node cannot run — so
;; this test pins the gate's ALGORITHM: the same comparison the Worker makes
;; (`mintCid(bytes) === cid`), expressed against the `kuro.host.cid`
;; reference mint. (1) a consistent cids/bytesByCid pair passes, (2) a pair
;; whose claimed CID belongs to different bytes fails. The browser E2E
;; (verify_opfs_browser.cljs :publishMismatchRefused) confirms the Worker
;; enforces it; this test keeps the contract visible on Node so a math drift
;; fails here without waiting for the E2E — same reasoning as cid-mint-parity.
(deftest publish-verifies-bytes-hash-to-their-claimed-cid
  (let [hello (js/Uint8Array. #js [104 101 108 108 111])  ; "hello"
        world (js/Uint8Array. #js [119 111 114 108 100])  ; "world"
        hello-cid (cid/sha256-raw-cid hello)
        world-cid (cid/sha256-raw-cid world)
        verify (fn [cids bytes-by-cid]
                 ;; the Worker's gate: every claimed cid must be present in
                 ;; bytesByCid AND hash to itself from the supplied bytes.
                 (every? (fn [c]
                           (let [b (get bytes-by-cid c)]
                             (and b (= c (cid/sha256-raw-cid b)))))
                         cids))]
    (testing "a consistent cids/bytesByCid pair passes the gate"
      (is (verify [hello-cid world-cid] {hello-cid hello world-cid world})))
    (testing "bytes that hash to a different CID are refused, not counted"
      (is (not (verify [hello-cid] {hello-cid world}))
          "claimed CID is hello's, bytes are world's — the gate must reject this pair"))
    (testing "a CID with no bytes supplied does not pass the gate"
      (is (not (verify [world-cid] {})))
      (is (not (verify [world-cid] {world-cid nil}))))
    (testing "the gate is byte-addressed: one flipped byte invalidates the pair"
      (let [tampered (js/Uint8Array. (aclone hello))]
        (aset tampered 0 72) ; "h" -> "H"
        (is (not= (cid/sha256-raw-cid tampered) hello-cid))
        (is (not (verify [hello-cid] {hello-cid tampered})))))))
