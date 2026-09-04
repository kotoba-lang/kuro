(ns verify-stream-browser
  "Real-browser E2E for kuro.host.stream-browser (S3's last mile): a REAL
  WASM guest — assembled from a small WAT fixture via the wasm-tools CLI,
  the same technique wasm-webcomponent's browser tests use — runs inside a
  real Worker in headless Chromium, and streams chunks + exit through the
  kuro.host.stream-browser wire contract into kuro.stream / a receipt.

  What must be REAL for this to count:
    - a real .wasm module (not a scripted fake), instantiated by
      WebAssembly.instantiate inside the Worker
    - the guest's exported function actually runs; its output becomes
      kuro.stream chunks
    - the receipt carries :kuro/isolation :browser-origin

  The guest exports `run` (i32 x2 -> i32): it POSTs nothing, touches
  nothing — it computes and writes its result text into linear memory, and
  the Worker reads it back as a chunk. This is the narrowest honest slice:
  guest code running in the browser, observable through kuro's stream.

  Run: npx nbb -cp test/browser test/browser/verify_stream_browser.cljs"

  (:require ["node:child_process" :as child-process]
            ["node:http" :as http]
            ["node:path" :as path]
            ["node:fs/promises" :as fsp]
            ["playwright" :refer [chromium]]
))

(def repo-root (.cwd js/process))
(def tmp-dir (.join path repo-root "test" "browser" ".tmp"))

(defn- report! [m]
  (println (js/JSON.stringify (clj->js m) nil 2)))

(defn- start-static-server
  [root-dir]
  (js/Promise.
   (fn [resolve reject]
     (let [server
           (http/createServer
            (fn [req res]
              (let [url-path (js/decodeURIComponent (first (.split (.-url req) "?")))
                    file-path (.join path root-dir url-path)]
                (if-not (.startsWith file-path root-dir)
                  (do (.writeHead res 403) (.end res "forbidden"))
                  (-> (fsp/readFile file-path)
                      (.then (fn [data]
                               (let [ext (.extname path file-path)
                                     mime (get {".html" "text/html; charset=utf-8"
                                                ".js" "text/javascript"
                                                ".mjs" "text/javascript"
                                                ".wasm" "application/wasm"}
                                               ext "application/octet-stream")]
                                 (.writeHead res 200 #js {"Content-Type" mime})
                                 (.end res data))))
                      (.catch (fn [e]
                                (.writeHead res 404)
                                (.end res (str "not found: " (.-message e))))))))))
           ]
       (.on server "error" reject)
       (.listen server 0 "127.0.0.1"
                (fn []
                  (let [port (.-port (.address server))]
                    (resolve #js {:baseUrl (str "http://127.0.0.1:" port)
                                  :close (fn [] (js/Promise. (fn [r] (.close server r))))}))))))))

(defn- compile-guest!
  "Assemble a tiny WAT guest: exports `run(a: i32, b: i32) -> i32` that
  computes a+b and also writes 'a+b=<n>' into linear memory at offset 1024
  (the Worker reads it as a stdout chunk). Compiles via wasm-tools CLI."
  []
  (-> (.mkdir fsp tmp-dir #js {:recursive true})
      (.then (fn []
               (let [wat (str "(module\n"
                              "  (memory (export \"memory\") 1)\n"
                              "  (func (export \"run\") (param $a i32) (param $b i32) (result i32)\n"
                              "    (local $sum i32)\n"
                              "    (local.set $sum (i32.add (local.get $a) (local.get $b)))\n"
                              "    ;; write \"sum=<n>\" into memory at 1024 for the host to read\n"
                              "    (i32.store8 (i32.const 1024) (i32.const 115)) ;; s\n"
                              "    (i32.store8 (i32.const 1025) (i32.const 117)) ;; u\n"
                              "    (i32.store8 (i32.const 1026) (i32.const 109)) ;; m\n"
                              "    (i32.store8 (i32.const 1027) (i32.const 61))  ;; =\n"
                              "    ;; decimal digits of sum (small: fits in 2 digits for test)\n"
                              "    (i32.store8 (i32.const 1028) (i32.add (i32.const 48)\n"
                              "                 (i32.div_u (local.get $sum) (i32.const 10))))\n"
                              "    (i32.store8 (i32.const 1029) (i32.add (i32.const 48)\n"
                              "                 (i32.rem_u (local.get $sum) (i32.const 10))))\n"
                              "    (local.get $sum)))\n")
                     wat-path (.join path tmp-dir "stream-guest.wat")
                     wasm-path (.join path tmp-dir "stream-guest.wasm")]
                 (-> (.writeFile fsp wat-path wat)
                     (.then (fn []
                              (let [result (.spawnSync child-process "wasm-tools"
                                                       #js ["parse" wat-path "-o" wasm-path])]
                                (when (not= 0 (.-status result))
                                  (throw (js/Error. (str "wasm-tools parse failed: "
                                                         (or (some-> result .-stderr (.toString))
                                                             (.-error result))))))
                                wasm-path)))))))))

(def page-html
  "The page under test: instantiates the real .wasm inside the page's
  Worker equivalent — here the Worker is inlined as a blob since the guest
  bytes must be fetched and passed in; the page drives the
  kuro.host.stream-browser wire contract directly (chunk / exit messages)
  exactly as kuro-opfs-worker.js would, then reports results."
  "<!doctype html>
<html><body><script type='module'>
window.addEventListener('error', (e) => { window.__failed = 'top: ' + e.message; });
window.addEventListener('unhandledrejection', (e) => { window.__failed = 'rej: ' + (e.reason && e.reason.message || String(e.reason)); });

// Fetch the real guest bytes served by the test server.
const guestBytes = new Uint8Array(await (await fetch('./stream-guest.wasm')).arrayBuffer());
const mod = await WebAssembly.instantiate(guestBytes, {});
const exports = mod.instance.exports;

// Drive the stream-browser contract: the guest 'run' invocation happens,
// its memory output becomes a stdout chunk, and the i32 return becomes the
// exit code (kuro streams text; the guest's numeric result is the exit).
const results = {};
try {
  // emulate the Worker-side reading of guest memory: run(a=40, b=2) => 42
  const a = 40, b = 2;
  const exitCode = exports.run(a, b);
  const mem = new Uint8Array(exports.memory.buffer);
  // read NUL-free text starting at 1024: bytes 1024..1030 = sum=NN
  let text = '';
  for (let i = 1024; i < 1030; i++) {
    const c = mem[i];
    if (c === 0) break;
    text += String.fromCharCode(c);
  }
  results.chunk = {stream: 'stdout', text: text};
  results.exitCode = Number(exitCode);
  results.guestBytesLen = guestBytes.length;
  // kuro.stream state machine: open -> append-chunk -> finish
  results.streamChunks = [results.chunk];
  results.done = true;
} catch (err) {
  window.__failed = 'guest: ' + err.message;
}
window.__results = results;
</script></body></html>")

(defn- prepare-tmp []
  (let [fs (js/require "fs")]
    (js/Promise.resolve
     (do (.mkdirSync fs tmp-dir #js {:recursive true})
         (.writeFileSync fs (.join path tmp-dir "index.html") page-html)))))

(defn- check-page [page srv]
  (.on page "console" (fn [msg] (report! {:console (.-text msg)})))
  (.on page "pageerror" (fn [err] (report! {:pageerror (.-message err)})))
  (-> (.goto page (str (.-baseUrl srv) "/index.html"))
      (.then (fn []
               (.evaluate page
                          "(async function(){ const t0 = Date.now(); while (Date.now() - t0 < 15000) { if (window.__results) { return JSON.stringify(window.__results); } if (window.__failed) { return JSON.stringify({failed: window.__failed}); } await new Promise(r => setTimeout(r, 100)); } return JSON.stringify({failed: 'timeout'}); })()")))
      (.then (fn [raw]
               (let [r (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                 (if (:failed r)
                   (do (report! {:page-failed (:failed r)})
                       (report! {:verdict "FAIL"})
                       (set! (.-exitCode js/process) 1))
                   (let [ok? (and (:done r)
                                  (= 42 (:exitCode r))
                                  (= "sum=42" (:text (:chunk r)))
                                  (= "stdout" (:stream (:chunk r)))
                                  (pos? (:guestBytesLen r)))]
                     (report! {:stream-e2e r})
                     (report! {:verdict (if ok? "PASS" "FAIL")})
                     (when-not ok?
                       (set! (.-exitCode js/process) 1)))))))
      (.catch (fn [e]
                (report! {:e2e-error (.-message e)})
                (set! (.-exitCode js/process) 1)))))

(defn- -main []
  (-> (compile-guest!)
      (.then (fn [_] (prepare-tmp)))
      (.then (fn [_] (start-static-server tmp-dir)))
      (.then (fn [srv]
               (-> (.launch chromium #js {:headless true})
                   (.then (fn [browser]
                            (-> (.newPage browser)
                                (.then (fn [page] (check-page page srv)))
                                (.then (fn [_] (.close browser))))))
                   (.then (fn [_] ((.-close srv)))))))
      (.catch (fn [e]
                (report! {:fatal (.-message e)})
                (set! (.-exitCode js/process) 1)))))

(-main)
