(ns kuro.host.stream-node
  "実行中のコマンドを本当に走らせる —— 逐次出力と stdin と kill。

  `kuro.host.node/run` は `spawnSync` で、終わるまで返らず、途中の出力も見えず、
  途中で止める手段も無かった。10 分の build と無限ループが呼び出し側から同じに
  見えるということで、それは端末ではない。

  ここは `spawn`（非同期）で、`kuro.stream` の値を進めながら chunk を渡す:

      (def h (start sess cmd {:repo-root \".\"
                              :on-chunk (fn [st chunk] …)
                              :on-exit  (fn [receipt] …)}))
      ((:write h) \"y\\n\")   ; stdin
      ((:kill h))            ; SIGTERM（既定）

  ## 保証は `kuro.host.node` と同じ

  capability の事前検査・argv（shell 無し）・cwd 拘束・宣言環境・時間上限・
  出力上限 —— 同じ関数を使い回しており、streaming 版だけ緩いということはない。

  ## PTY ではない（重要）

  子プロセスに繋がるのは **pipe** であって疑似端末ではない。したがって:
  `isatty` は偽になり、多くのプログラムは色付けを自分から止め、行バッファに
  切り替わる。`vim` や `top` のような全画面プログラムは動かない。SIGWINCH も
  端末サイズも無い。**本物の PTY には native addon（node-pty 等）が要り、それは
  この repo の依存方針に関わる別の決定**なので、ここでは踏み込まない。
  `TERM=dumb` を宣言環境に入れて、子に嘘をつかないようにしている。"
  (:require ["node:child_process" :as cp]
            [kuro.host.cid :as cid]
            [kuro.host.node :as node]
            [kuro.stream :as stream]
            [kuro.terminal :as t]))

(def default-env
  "streaming 実行の宣言環境。`kuro.host.node/default-env` に `TERM=dumb` を足す。

  pipe で繋いでおきながら `TERM=xterm-256color` と名乗ると、子は cursor 移動や
  代替画面を出してくる —— こちらはそれを解釈できない（`kuro.ansi` は行志向）。
  能力を偽らない方が出力が読める。"
  (assoc node/default-env "TERM" "dumb"))

(defn start
  "`cmd` を非同期に開始する。戻り値は
  `{:stream <atom of kuro.stream> :write fn :kill fn :pid n}`、
  capability 不足なら `kuro.terminal/denial`（**spawn しない**）。

  opts: `:repo-root` `:env` `:timeout-ms` `:max-output-bytes` `:now`
        `:on-chunk` `(fn [stream-state chunk])`
        `:on-exit`  `(fn [receipt])`"
  [sess cmd opts]
  (when (= :terminal-host (:kuro/mode sess))
    (throw (ex-info "kuro.host.stream-node has no host-shell backing"
                    {:mode :terminal-host :reason :unsupported-mode})))
  (let [cwd (or (node/confine (:repo-root opts ".") (:kuro/cwd sess))
                (throw (ex-info "terminal cwd escapes the repo root"
                                {:reason :cwd-escape :cwd (:kuro/cwd sess)})))
        required (node/required-capabilities cmd)]
    (or (t/denial sess required)
        (let [now (:now opts #(js/Date.now))
              max-bytes (:max-output-bytes opts stream/default-max-output-bytes)
              st (atom (stream/open sess cmd {:max-output-bytes max-bytes}))
              started (now)
              argv (:kuro/argv cmd)
              on-chunk (:on-chunk opts (fn [_ _]))
              on-exit (:on-exit opts (fn [_]))
              done? (atom false)
              proc (cp/spawn (first argv) (clj->js (vec (rest argv)))
                             #js {:cwd cwd
                                  :env (clj->js (:env opts default-env))
                                  :shell false
                                  :stdio #js ["pipe" "pipe" "pipe"]})
              take-chunk!
              (fn [stream-kw]
                (fn [buf]
                  (let [chunk {:stream stream-kw :text (.toString buf "utf8")}]
                    (swap! st stream/append-chunk chunk)
                    (on-chunk @st chunk)
                    ;; 上限に達したら**こちらから止める**。spawn の maxBuffer と
                    ;; 違い非同期版は勝手に殺してくれないので、無限に吐く子が
                    ;; あるとメモリではなく時間だけが溶ける。
                    (when (and (:kuro/truncated? @st) (not @done?))
                      (.kill proc "SIGKILL")))))
              finish!
              (fn [result]
                (when-not @done?
                  (reset! done? true)
                  (let [finished (now)
                        ;; README「content-addressed output」は sync 側だけの
                        ;; 保証にしない: streaming の receipt も sync と同じ
                        ;; CIDv1/raw/sha2-256 を持つ。text-of は UTF-8 文字列を
                        ;; 返し、text-cid はその UTF-8 byte 列にハッシュする ——
                        ;; sync 側が Buffer にハッシュするのと同じ byte 列。
                        stdout-cid (cid/text-cid (stream/text-of @st :stdout))
                        stderr-cid (cid/text-cid (stream/text-of @st :stderr))
                        receipt (stream/finish @st (merge {:started-at started
                                                           :finished-at finished
                                                           :duration-ms (- finished started)
                                                           :stdout-cid stdout-cid
                                                           :stderr-cid stderr-cid}
                                                          result))]
                    (swap! st stream/mark-finished)
                    (on-exit receipt)
                    receipt)))
              timer (when-let [ms (:timeout-ms opts 120000)]
                      (js/setTimeout
                       (fn []
                         (when-not @done?
                           (.kill proc "SIGKILL")
                           (finish! {:exit-code 124 :timed-out? true})))
                       ms))]
          (.on (.-stdout proc) "data" (take-chunk! :stdout))
          (.on (.-stderr proc) "data" (take-chunk! :stderr))
          (.on proc "error"
               (fn [err]
                 (when timer (js/clearTimeout timer))
                 (finish! {:exit-code (if (= "ENOENT" (.-code err)) 127 126)
                           :error (if (= "ENOENT" (.-code err))
                                    (str "command not found: " (first argv))
                                    (str (.-code err)))})))
          (.on proc "close"
               (fn [code signal]
                 (when timer (js/clearTimeout timer))
                 (finish!
                  (cond
                    (:kuro/truncated? @st) {:exit-code 125 :truncated? true}
                    (some? code) {:exit-code code}
                    ;; kill されたら signal 名を残す。exit code だけだと
                    ;; 「誰が止めたのか」が receipt から消える。
                    :else {:exit-code 128 :error (str "terminated by " signal)}))))
          {:stream st
           :pid (.-pid proc)
           :write (fn [s]
                    (when-let [stdin (.-stdin proc)]
                      (.write stdin s)))
           :close-stdin (fn [] (some-> (.-stdin proc) (.end)))
           :kill (fn
                   ([] (.kill proc "SIGTERM"))
                   ([sig] (.kill proc sig)))}))))

(defn run-async
  "`start` を Promise で包む。receipt に解決する（denial は即座に解決）。"
  [sess cmd opts]
  (js/Promise.
   (fn [resolve _reject]
     (let [h (start sess cmd (assoc opts :on-exit resolve))]
       (when (false? (:kuro/allowed? h)) (resolve h))))))
