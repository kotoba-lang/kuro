(ns kuro.host.stream-browser-test
  "Tests for the browser terminal host's decision logic, runnable on Node
  (nbb). The Worker/WASM instantiation itself is browser-only; what Node
  proves here:

    - argv-style commands are DENIED with no worker created (the honest
      refusal — a browser has no processes)
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

(deftest terminal-host-mode-refused
  (let [host-sess (t/session "s2" "bafyrei-root" :terminal-host
                             {:kuro/signed-opt-in? true})]
    (is (= false (:kuro/allowed?
                  (sb/start host-sess guest {:make-worker (fn [_] nil)}))))))

(deftest guest-run-produces-chunks-and-isolated-receipt
  (let [sent (atom [])
        chunks (atom [])
        exit-receipt (atom nil)
        replies [{"kuro.browser/type" "chunk" "stream" "stdout" "text" "hello from wasm"}
                 {"kuro.browser/type" "chunk" "stream" "stderr" "text" "warn"}
                 {"kuro.browser/type" "exit" "exit-code" 0 "started-at" 100 "finished-at" 150}]
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
    (testing "stdin was forwarded to the worker while running"
      (is (= "stdin" (:kuro.browser/type (first @sent)))))
    (testing "cancel was forwarded"
      (is (= "cancel" (:kuro.browser/type (second @sent)))))
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
        replies [{"kuro.browser/type" "exit" "exit-code" 0}]
        emit (atom nil)
        h (sb/start sess guest {:make-worker (make-worker-fn replies sent emit)})
        _ (@emit)]
    ;; drain the synchronous replies
    (is (not (stream/running? @(:stream h))))
    ((:write h) "late stdin")
    ((:kill h))
    (testing "nothing was sent to the dead guest"
      (is (empty? @sent)))))

(deftest double-exit-produces-one-receipt
  (let [exits (atom 0)
        replies [{"kuro.browser/type" "exit" "exit-code" 0}
                 {"kuro.browser/type" "exit" "exit-code" 0}]
        emit (atom nil)
        _ (sb/start sess guest
                    {:make-worker (make-worker-fn replies nil emit)
                     :on-exit (fn [_] (swap! exits inc))})
        _ (@emit)]
    (is (= 1 @exits) "the second :exit is ignored, not a second receipt")))
