(ns kuro.host.stream-browser-test
  "Tests for the browser terminal host's decision logic, runnable on Node
  (nbb). The Worker/WASM instantiation itself is browser-only; what Node
  proves here:

    - argv-style commands are DENIED with no worker created (the honest
      refusal — a browser has no processes)
    - a command that is neither argv nor a guest spec is denied with
      :guest-spec-required (the third refusal branch)
    - :terminal-host mode is refused
    - a guest invocation wires chunk -> kuro.stream, exit -> receipt with
      :kuro/isolation :browser-origin
    - write/kill after exit are silent no-ops (no stdin to a dead guest)
    - the exit handler runs exactly once even if the Worker replies twice"
  (:require [cljs.test :refer [deftest is testing]]
            [kuro.stream :as stream]
            [kuro.host.stream-browser :as sb]
            [kuro.terminal :as t]))

(def sess
  (t/session "s1" "bafyrei-root" :terminal-agent
             {:kuro/grant {:capabilities #{"repo/read" "tmp/write" "log/write"
                                           "agent/checkpoint" "guest/invoke"}}}))

(def guest {:kuro.browser/guest "bafybei-component"
            :kuro.browser/export "run"
            :kuro.browser/args ["x"]})

(defn fake-worker
  "A Worker stand-in whose postMessage delivers scripted replies through the
  onmessage listener the host installed. Records everything sent to
  `sent-ref` (the test's own atom — no nested-atom indirection). Built as a
  real JS object so the host's (.-addEventListener w) interop finds the
  methods, exactly as it would on a real Worker."
  [replies sent-ref]
  (let [listeners (atom [])]
    #js {:addEventListener (fn [_ev f] (swap! listeners conj f))
         :postMessage (fn [msg]
                        (swap! sent-ref conj (js->clj msg :keywordize-keys true)))
         ;; emit the scripted replies asynchronously, the way a real Worker
         ;; does: after start returns, on the macrotask queue.
         :_emit (fn []
                  (doseq [f @listeners]
                    (doseq [r replies]
                      (f #js {:data (clj->js r)}))))}))

(defn- make-worker-fn
  ([replies emit-ref] (make-worker-fn replies nil emit-ref))
  ([replies sent-ref emit-ref]
   (fn [_worker-file]
     (let [w (fake-worker replies (or sent-ref (atom [])))]
       (when emit-ref (reset! emit-ref (.-_emit w)))
       w))))

(deftest argv-is-denied-without-creating-a-worker
  (let [created (atom 0)
        result (sb/start sess ["echo" "hi"]
                         {:make-worker (fn [_] (swap! created inc) nil)})]
    (testing "argv-style command is a denial, not a spawn"
      (is (= false (:kuro/allowed? result)))
      (is (= :argv-not-a-process (:kuro/reason result))))
    (testing "no worker was created"
      (is (zero? @created)))))

(deftest command-that-is-neither-argv-nor-guest-spec-is-denied
  ;; README「Commands are guest invocation specs … argv vectors are denied
  ;; with :argv-not-a-process」は argv を pin するが、argv でも guest spec でも
  ;; 無い第 3 の形は `:guest-spec-required` の branch に落ちる。この branch に
  ;; test が無いと、denial の語 (:kuro/reason) を黙って変えても誰も気づかない
  ;; —— 呼び出し側が分岐する語なので pin する。worker は 1 個も作らない。
  (let [created (atom 0)
        start* (fn [cmd] (sb/start sess cmd
                                   {:make-worker (fn [_] (swap! created inc) nil)}))]
    (testing "a bare string command is refused as :guest-spec-required"
      (let [r (start* "echo hi")]
        (is (false? (:kuro/allowed? r)))
        (is (= :guest-spec-required (:kuro/reason r)))))
    (testing "nil is refused the same way"
      (let [r (start* nil)]
        (is (false? (:kuro/allowed? r)))
        (is (= :guest-spec-required (:kuro/reason r)))))
    (testing "a map with a guest but no export is not a guest spec"
      (let [r (start* {:kuro.browser/guest "bafybei-component"})]
        (is (false? (:kuro/allowed? r)))
        (is (= :guest-spec-required (:kuro/reason r)))))
    (testing "no worker was created for any of them"
      (is (zero? @created)))))

(deftest terminal-host-mode-refused
  (let [host-sess (t/session "s2" "bafyrei-root" :terminal-host
                             {:kuro/signed-opt-in? true})]
    (is (= false (:kuro/allowed?
                  (sb/start host-sess guest {:make-worker (fn [_] nil)}))))))

(deftest guest-run-produces-chunks-and-isolated-receipt
  (let [sent (atom [])
        chunks (atom [])
        exit-receipt (atom nil)
        replies [{"kuro.stream/type" "chunk" "kuro.stream/stream" "stdout" "kuro.stream/text" "hello from wasm"}
                 {"kuro.stream/type" "chunk" "kuro.stream/stream" "stderr" "kuro.stream/text" "warn"}
                 {"kuro.stream/type" "exit" "kuro.stream/exit-code" 0 "kuro.stream/started-at" 100 "kuro.stream/finished-at" 150}]
        emit (atom nil)
        h (sb/start sess guest
                    {:make-worker (make-worker-fn replies sent emit)
                     :on-chunk (fn [_st chunk] (swap! chunks conj chunk))
                     :on-exit (fn [receipt] (reset! exit-receipt receipt))})]
    ;; stdin and cancel happen while running — before the exit reply lands.
    ((:write h) "y\n")
    ((:kill h))
    (@emit)
    (let [final @(:stream h)]
    (testing "a start request was sent at construction"
      (is (= "start" (:kuro.stream/op (first @sent)))))
    (testing "stdin was forwarded to the worker while running"
      (is (= "stdin" (:kuro.stream/op (second @sent)))))
    (testing "cancel was forwarded"
      (is (= "cancel" (:kuro.stream/op (nth @sent 2)))))
    (testing "chunks flowed through kuro.stream"
      (is (= 2 (count @chunks))))
    (testing "receipt is terminal-shaped and names its isolation"
      (is (= :kuro/receipt (:kuro/type @exit-receipt)))
      (is (= :browser-origin (:kuro/isolation @exit-receipt)))
      (is (zero? (:kuro/exit-code @exit-receipt))))
    (testing "stdout text was folded from the chunks"
      (is (= "hello from wasm" (:kuro/stdout @exit-receipt))))
    (testing "final stream value is the receipt"
      (is (= :kuro/receipt (:kuro/type final)))))))

(deftest write-and-kill-after-exit-are-noops
  (let [sent (atom nil)
        replies [{"kuro.stream/type" "exit" "kuro.stream/exit-code" 0}]
        emit (atom nil)
        h (sb/start sess guest {:make-worker (make-worker-fn replies sent emit)})
        _ (@emit)]
    ;; drain the synchronous replies
    (is (not (stream/running? @(:stream h))))
    ((:write h) "late stdin")
    ((:kill h))
    (testing "only the construction-time start request was sent"
      (is (= ["start"] (mapv :kuro.stream/op @sent))))))

(deftest double-exit-produces-one-receipt
  (let [exits (atom 0)
        replies [{"kuro.stream/type" "exit" "kuro.stream/exit-code" 0}
                 {"kuro.stream/type" "exit" "kuro.stream/exit-code" 0}]
        emit (atom nil)
        _ (sb/start sess guest
                    {:make-worker (make-worker-fn replies nil emit)
                     :on-exit (fn [_] (swap! exits inc))})
        _ (@emit)]
    (is (= 1 @exits) "the second :exit is ignored, not a second receipt")))

(deftest timeout-ms-is-not-a-claim-this-host-makes
  ;; NB the docstring (and this test): the browser host deliberately takes no
  ;; :timeout-ms. The stream-node provider documents and enforces a deadline
  ;; (120 s default, exit 124); this host CANNOT — the guest runs to
  ;; completion synchronously inside the Worker, so no main-thread timer can
  ;; preempt it, and claiming a timeout would make an unbounded receipt read
  ;; as though it had bounded time. A regression that silently started
  ;; interpreting :timeout-ms would either lie about a constraint it cannot
  ;; enforce or crash the guest mid-run; pin that the option does not exist
  ;; on this contract (the guest still runs and exits normally).
  (testing "the guest still runs to its own exit when :timeout-ms is supplied"
    (let [exit-receipt (atom nil)
          replies [{"kuro.stream/type" "exit" "kuro.stream/exit-code" 7}]
          emit (atom nil)
          _ (sb/start sess guest
                      {:make-worker (make-worker-fn replies nil emit)
                       :timeout-ms 1
                       :on-exit (fn [r] (reset! exit-receipt r))})
          _ (@emit)]
      (is (= 7 (:kuro/exit-code @exit-receipt))
          "the guest is NOT cut short by an (unenforceable) deadline — it ended itself"))))

(deftest caller-max-output-bytes-caps-the-guest-and-records-it
  ;; stream-browser/start docstring lists `:max-output-bytes` as a public
  ;; option wired into `stream/open`. The Node providers pin their cap tests on
  ;; both sides (node_test and stream_node_test cover the flood + truncation +
  ;; the dropped-byte accounting). This host accepts the same option but had NO
  ;; test wiring it through: if the `cond->` in `start` silently stopped
  ;; forwarding the caller's cap, the browser would just fall back to the 1 MiB
  ;; default and a receipt would claim a bound nobody asked for. Pin that the
  ;; supplied cap reaches `stream/open` and that a guest flooding past it is
  ;; recorded, not silently cut (kuro.stream's own rule: a silently-cut receipt
  ;; is indistinguishable from a short success).
  (let [exit-receipt (atom nil)
        flood (apply str (repeat 40 "x"))
        replies [{"kuro.stream/type" "chunk" "kuro.stream/stream" "stdout"
                  "kuro.stream/text" flood}
                 {"kuro.stream/type" "exit" "kuro.stream/exit-code" 0}]
        emit (atom nil)
        _ (sb/start sess guest
                    {:make-worker (make-worker-fn replies nil emit)
                     :max-output-bytes 8
                     :on-exit (fn [r] (reset! exit-receipt r))})
        _ (@emit)]
    (testing "the caller's cap, not the 1 MiB default, bounds the guest"
      (is (true? (:kuro/truncated? @exit-receipt))
          "the cap fired - truncation is on the record, not omitted")
      (is (= 40 (:kuro/dropped-bytes @exit-receipt))
          "the bytes dropped past the cap are counted, not hidden")
      (is (empty? (:kuro/stdout @exit-receipt))
          "the kept stream is an exact prefix of what was emitted (empty here)"))))
