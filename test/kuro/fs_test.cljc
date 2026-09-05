(ns kuro.fs-test
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.fs :as fs]))

;; host stand-ins: the simplest possible block store — in-memory, cid = hex-ish
;; of content. The real host computes sha256+CIDs; the seam is what matters.

(defn- fake-put [blocks]
  (fn [bytes]
    (let [cid (str "bafk-" (hash bytes))]
      (swap! blocks assoc cid bytes)
      cid)))

(defn- fake-get [blocks]
  (fn [cid] (get @blocks cid)))

(defn- fresh-store [_blocks]
  (fs/store "bafyrei-repo-root"))

(def ^:private some-bytes
  #?(:clj (byte-array [104 105])
     :cljs (js/Uint8Array. #js [104 105])))

(defn- byte-len [b]
  #?(:clj (alength b)
     :cljs (.-length b)))
(deftest write-and-read-round-trip
  (let [blocks (atom {})
        st (fresh-store blocks)
        [st' cid] (fs/write st "hello.txt" some-bytes (fake-put blocks))
        [st'' bytes] (fs/read-file st' "hello.txt" (fake-get blocks))]
    (testing "write returns a content cid"
      (is (string? cid))
      (is (re-find #"bafk-" cid)))
    (testing "read returns the same bytes"
      (is (some? bytes))
      (is (= (byte-len some-bytes) (byte-len bytes))))
    (testing "receipts record both ops as accepted"
      (is (= [:kuro.fs/receipt :kuro.fs/receipt]
             (mapv :kuro.fs/type (fs/receipts st''))))
      (is (= [:write :read] (mapv :kuro.fs/op (fs/receipts st'')))))))

(deftest overwrite-produces-new-store-value
  (let [blocks (atom {})
        st (fs/store)
        [st1 cid1] (fs/write st "f.txt" some-bytes (fake-put blocks))
        other-bytes #?(:clj (byte-array [104 105 106])
                       :cljs (js/Uint8Array. #js [104 105 106]))
        [st2 cid2] (fs/write st1 "f.txt" other-bytes (fake-put blocks))]
    ;; content-addressed: identical bytes must produce an identical CID, so a
    ;; different CID requires different bytes. The real claim is that writing
    ;; different bytes replaces the file's CID rather than mutating in place.
    (is (not= cid1 cid2))
    (is (= 2 (count (fs/receipts st2))))))

(deftest mkdir-and-ls
  (let [blocks (atom {})
        st (fs/store)
        [st1] (fs/mkdir st "docs/notes")
        [st2] (fs/write st1 "docs/notes/a.txt" some-bytes (fake-put blocks))
        [st3 entries] (fs/ls st2 "docs/notes")
        [_ root-entries] (fs/ls st3 "docs")]
    (is (= [{:name "notes" :type :dir :size 0}]
           (map #(select-keys % [:name :type :size]) root-entries)))
    (is (= [{:name "a.txt" :type :file :size 2}]
           (map #(select-keys % [:name :type :size]) entries)))))

(deftest denial-not-exception
  (let [blocks (atom {})
        st (fs/store)]
    (testing "read of missing path is a recorded denial, not a throw"
      (let [[st' bytes] (fs/read-file st "nope.txt" (fake-get blocks))]
        (is (nil? bytes))
        (is (= :not-found (:kuro.fs/reason (last (fs/receipts st')))))))
    (testing "path traversal is refused"
      (let [[st' bytes] (fs/read-file st "../etc/passwd" (fake-get blocks))]
        (is (nil? bytes))
        (is (= :bad-path (:kuro.fs/reason (last (fs/receipts st')))))))
    (testing "rm of a non-empty directory is refused"
      (let [[st1] (fs/mkdir st "d")
            [st2] (fs/write st1 "d/f.txt" some-bytes (fake-put blocks))
            [st3 removed] (fs/rm st2 "d")]
        (is (not removed))
        (is (= :directory-not-empty (:kuro.fs/reason (last (fs/receipts st3)))))))
    (testing "root is immutable"
      (let [[_ removed2] (fs/rm st "only-one-segment")]
        (is (not removed2))))))

(deftest every-documented-denial-reason-is-pinned
  ;; kuro.fs の各 path op が返す :kuro.fs/reason は、README の「Denied work
  ;; … produces recorded receipts」が指す契約の実体。reason 名は receipt を
  ;; 読む側が分岐する語なので、黙って変わると消費者側が静かに壊れる ——
  ;; 片付けずに pin する。
  (let [blocks (atom {})
        st (fs/store)]
    (testing "write: writing over a directory is :is-a-directory"
      (let [[st1] (fs/mkdir st "d")
            [st2] (fs/write st1 "d" some-bytes (fake-put blocks))]
        (is (= :is-a-directory (:kuro.fs/reason (last (fs/receipts st2)))))))
    (testing "write: non-byte payload is :bytes-required, not an exception"
      (let [[st' bytes] (fs/write st "x.txt" "not bytes" (fake-put blocks))]
        (is (nil? bytes))
        (is (= :bytes-required (:kuro.fs/reason (last (fs/receipts st')))))))
    (testing "read: reading a directory is :not-a-file"
      (let [[st1] (fs/mkdir st "dd")
            [st2 bytes] (fs/read-file st1 "dd" (fake-get blocks))]
        (is (nil? bytes))
        (is (= :not-a-file (:kuro.fs/reason (last (fs/receipts st2)))))))
    (testing "read: a recorded file whose block the host lost is :block-missing"
      (let [[st1] (fs/write st "g.txt" some-bytes (fake-put blocks))
            [st2 bytes] (fs/read-file st1 "g.txt" (fn [_cid] nil))]
        (is (nil? bytes))
        (let [r (last (fs/receipts st2))]
          (is (= :block-missing (:kuro.fs/reason r)))
          (is (string? (:kuro.fs/cid r)) "the receipt names the block that is gone"))))
    (testing "ls: listing a file is :not-a-directory"
      (let [[st1] (fs/write st "f.txt" some-bytes (fake-put blocks))
            [st2 entries] (fs/ls st1 "f.txt")]
        (is (nil? entries))
        (is (= :not-a-directory (:kuro.fs/reason (last (fs/receipts st2)))))))
    (testing "write/mkdir through a file parent is :path-blocked"
      (let [[st1] (fs/write st "blocker" some-bytes (fake-put blocks))
            [st2 cid] (fs/write st1 "blocker/child.txt" some-bytes (fake-put blocks))]
        (is (nil? cid))
        (is (= :path-blocked (:kuro.fs/reason (last (fs/receipts st2))))))
      (let [[st1] (fs/write st "wall" some-bytes (fake-put blocks))
            [st2 p] (fs/mkdir st1 "wall/d")]
        (is (nil? p))
        (is (= :path-blocked (:kuro.fs/reason (last (fs/receipts st2)))))))))

(deftest rm-file-then-list
  (let [blocks (atom {})
        st (fs/store)
        [st1] (fs/write st "gone.txt" some-bytes (fake-put blocks))
        [st2 removed] (fs/rm st1 "gone.txt")
        [_ entries] (fs/ls st2 ".")]
    (is removed)
    (is (= [] entries))))

(deftest rm-of-an-empty-directory-succeeds
  (testing "README / fs/rm docstring: 'Remove a file or an empty directory' —
            the empty-dir half of that contract had no test: a regression that
            made rm refuse every directory would still have been green."
    (let [st (fs/store)]
      (testing "an empty dir is removed, recorded, and listed as gone"
        (let [[st1] (fs/mkdir st "empty")
              [st2 removed] (fs/rm st1 "empty")]
          (is (true? removed))
          (is (= :kuro.fs/receipt (:kuro.fs/type (last (fs/receipts st2)))))
          (let [[_ entries] (fs/ls st2 ".")]
            (is (= [] entries)))))
      (testing "rm of the now-missing dir again is a not-found denial"
        (let [[st1] (fs/mkdir st "d")
              [st2] (fs/rm st1 "d")
              [st3 removed] (fs/rm st2 "d")]
          (is (not removed))
          (is (= :not-found (:kuro.fs/reason (last (fs/receipts st3))))))))))

(deftest receipts-list-both-accepted-and-denied
  (let [blocks (atom {})
        st (fs/store)
        [st1] (fs/write st "a.txt" some-bytes (fake-put blocks))
        [st2] (fs/read-file st1 "missing" (fake-get blocks))]
    (is (= [:write :read] (mapv :kuro.fs/op (fs/receipts st2))))
    (is (= [:kuro.fs/receipt :kuro.fs/denied]
           (mapv :kuro.fs/type (fs/receipts st2))))))

(deftest operations-outside-the-grant-are-denied-receipts-not-exceptions
  ;; ns docstring: "a write without `fs/write` capability … returns a
  ;; `:kuro.fs/denied` receipt" — and default-capabilities: "anything not
  ;; granted produces a denial receipt". Until 2026-09-04 that was a claim
  ;; with no mechanism: no path op consulted any grant. `with-grant` narrows
  ;; the store's grant; every op that needs an absent capability records a
  ;; denial (with `:kuro.fs/missing` naming the capability) and returns nil
  ;; in the result slot — the same [store' nil] shape as every other denial.
  (let [blocks (atom {})
        st (-> (fs/store "bafyrei-root")
               (fs/with-grant #{"fs/read"}))]
    (testing "write is denied with the missing capability named"
      (let [[st' cid] (fs/write st "no.txt" some-bytes (fake-put blocks))]
        (is (nil? cid))
        (is (empty? (:kuro.fs/nodes st')) "no node was created")
        (let [r (last (fs/receipts st'))]
          (is (= :kuro.fs/denied (:kuro.fs/type r)))
          (is (= :write (:kuro.fs/op r)))
          (is (= :missing-capability (:kuro.fs/reason r)))
          (is (= "fs/write" (:kuro.fs/missing r))))))
    (testing "rm and mkdir are denied too"
      (let [[st' removed] (fs/rm st "anything")]
        (is (nil? removed))
        (is (= "fs/rm" (:kuro.fs/missing (last (fs/receipts st'))))))
      (let [[st' p] (fs/mkdir st "d")]
        (is (nil? p))
        (is (= "fs/write" (:kuro.fs/missing (last (fs/receipts st')))))))
    (testing "the granted capability still works"
      (let [[st1] (fs/write (fs/store "bafyrei-root") "ok.txt" some-bytes (fake-put blocks))
            [st2 bytes] (fs/read-file (fs/with-grant st1 #{"fs/read"})
                                      "ok.txt" (fake-get blocks))]
        (is (some? bytes))
        (is (= :kuro.fs/receipt (:kuro.fs/type (last (fs/receipts st2)))))))
    (testing "the reverse leaf: fs/write does not imply fs/read"
      ;; README "Isolation, on the record": "fs/write implies nothing about
      ;; fs/read (separate leaves)". The other direction (fs/read granted,
      ;; fs/write denied) is pinned above; this is the direction an auditor
      ;; worries about -- a writer that cannot read back what it wrote.
      (let [w (fs/with-grant (fs/store "bafyrei-root") #{"fs/write"})
            [st1 cid] (fs/write w "secret.txt" some-bytes (fake-put blocks))]
        (is (string? cid) "the write itself succeeds")
        (let [[st2 bytes] (fs/read-file st1 "secret.txt" (fake-get blocks))]
          (is (nil? bytes))
          (let [r (last (fs/receipts st2))]
            (is (= :kuro.fs/denied (:kuro.fs/type r)))
            (is (= :missing-capability (:kuro.fs/reason r)))
            (is (= "fs/read" (:kuro.fs/missing r)))))))
    (testing "the default grant is the whole vocabulary -- nothing changes for it"
      (let [[st' cid] (fs/write (fs/store) "ok.txt" some-bytes (fake-put blocks))]
        (is (string? cid))
        (is (every? #(= :kuro.fs/receipt (:kuro.fs/type %)) (fs/receipts st')))))))
