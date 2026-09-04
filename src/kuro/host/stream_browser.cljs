(ns kuro.host.stream-browser
  "Terminal host backed by a WASM guest component in a browser Worker (S3 of
  ADR-2609041240).

  ## The shape, borrowed from stream-node

  `kuro.host.stream-node` owns the same problem on Node: start work, feed
  stdin, receive chunks as they happen, kill, finish with a receipt. This
  namespace keeps that shape and changes the backing:

      (def h (start sess cmd {:on-chunk … :on-exit …}))
      ((:write h) \"y\\n\")   ; stdin
      ((:kill h))            ; cancel the guest

  Instead of `node:child_process`.spawn it drives a **guest component**
  (compiled `.wasm`, per `kuro.host.opfs`'s block store and the
  wasm-webcomponent actor-host pattern) inside the same Worker that holds
  OPFS. The guest receives `on-http`-style `run` invocations; the host maps
  its outputs to `kuro.stream` chunks.

  ## What a \"command\" means here

  There is no process to spawn in a browser, and `kuro.host.stream-node`'s
  honest docstring already refuses `:terminal-host` mode because a browser
  tab has no shell. So this host **refuses argv-style commands by design**
  and accepts a guest invocation spec instead:

      {:kuro.browser/guest \"bafybei…\"        ; component CID
       :kuro.browser/export \"run\"            ; export to call
       :kuro.browser/args  [\"…\"]}            ; scalar args

  Calling `start` with an argv vector is a `:argv-not-a-process` denial —
  the browser does not have processes, and pretending otherwise would make
  every receipt lie about what ran.

  ## Isolation, on the record

  Every receipt this host produces carries
  `:kuro/isolation :browser-origin` (CLAUDE.md rule: a receipt that does not
  record isolation reads as if it were isolated). What that value MEANS:

    - the guest ran in a Worker with no DOM, no ambient authority
    - its effects were the declared host imports only (capability-gated)
    - same-origin storage (OPFS) was reachable only through those imports

  It does NOT mean a kernel-level sandbox; kobo's \"capability set is an
  intent record, not a kernel\" still holds. The value records WHERE the
  boundary was, and the capability set records WHAT crossed it.

  ## Pure vs effects

  The decision logic lives in `kuro.terminal` / `kuro.stream` (pure). This
  namespace is ClojureScript-only and browser-only: Worker, postMessage,
  WebAssembly instantiation are effects it performs on behalf of the model."
  (:require [kuro.stream :as stream]
            [kuro.terminal :as t]))

(def worker-file
  "The Worker script this host expects the page to serve (compiled guest
  loading + kuro.opfs block-store message handling). Kept as a constant so
  page glue and E2E cannot drift apart."
  "kuro-stream-worker.js")

(defn- guest-spec? [cmd]
  (and (map? cmd)
       (string? (:kuro.browser/guest cmd))
       (string? (:kuro.browser/export cmd))))

(defn start
  "Start a guest invocation. Returns
  `{:stream <atom of kuro.stream> :write fn :kill fn :worker <Worker>}` or a
  `kuro.terminal/denial` (no spawn happened).

  opts: `:make-worker` `(fn [worker-file] Worker)` — injected so tests can
  substitute a fake; `:timeout-ms`; `:max-output-bytes`; `:now`;
  `:on-chunk` `(fn [stream-state chunk])`; `:on-exit` `(fn [receipt])`."
  [sess cmd opts]
  (cond
    ;; argv-style: the honest refusal. A browser has no processes to spawn —
    ;; this is a category error, not a missing capability, so it denies even
    ;; when guest/invoke is granted.
    (vector? cmd)
    {:kuro/allowed? false
     :kuro/reason :argv-not-a-process
     :kuro/session-id (:kuro/session-id sess)}
    ;; wrong mode: stream-node already refuses :terminal-host; so does this.
    (= :terminal-host (:kuro/mode sess))
    (t/denial sess #{"guest/invoke"})
    ;; not a guest spec at all
    (not (guest-spec? cmd))
    {:kuro/allowed? false
     :kuro/reason :guest-spec-required
     :kuro/session-id (:kuro/session-id sess)}
    ;; capability gate
    :else (or (t/denial sess #{"guest/invoke"})
              (let [st (atom (stream/open sess cmd
                                          (cond-> {}
                                            (:max-output-bytes opts)
                                            (assoc :max-output-bytes (:max-output-bytes opts)))))
                    exits (atom 0)
                    ;; the wire: Worker replies with
                    ;; {:kuro.browser/type :chunk :stream :stdout|:stderr :text "…"}
                    ;; {:kuro.browser/type :exit  :exit-code n :started-at … :finished-at …}
                    on-message (fn [ev]
                                 ;; The wire uses plain string keys — clj->js nests
                                 ;; namespaced keywords into objects, so a
                                 ;; :kuro.browser/type keyword sent from CLJS would
                                 ;; arrive as {kuro {browser {type ...}}}. Strings
                                 ;; round-trip unchanged.
                                 (let [m (js->clj (.-data ev))
                                       type (get m "kuro.browser/type")]
                                   (condp = type
                                     "chunk"
                                     (let [chunk {:stream (keyword (get m "stream"))
                                                  :text (get m "text")}]
                                       (swap! st stream/append-chunk chunk)
                                       (when-let [cb (:on-chunk opts)]
                                         (cb @st chunk)))
                                     "exit"
                                     (when (== 1 (swap! exits inc))
                                       (let [result (cond-> {:exit-code (get m "exit-code" 0)
                                                             :isolation :browser-origin}
                                                      (get m "started-at") (assoc :started-at (get m "started-at"))
                                                      (get m "finished-at") (assoc :finished-at (get m "finished-at")))
                                             receipt (stream/finish @st result)]
                                         (reset! st receipt)
                                         (when-let [cb (:on-exit opts)]
                                           (cb receipt))))
                                     nil)))
                    worker ((:make-worker opts) worker-file)
                    add-l (.-addEventListener worker)
                    _ (.call add-l worker "message" on-message)]
                {:stream st
                 :write (fn [text]
                          (when (stream/running? @st)
                            (let [pm (.-postMessage worker)]
                              (.call pm worker #js {"kuro.browser/type" "stdin"
                                                    "kuro.browser/text" text}))))
                 :kill (fn []
                         (when (stream/running? @st)
                           (let [pm (.-postMessage worker)]
                             (.call pm worker #js {"kuro.browser/type" "cancel"}))))
                 :worker worker}))))
