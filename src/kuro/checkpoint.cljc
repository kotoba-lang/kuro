(ns kuro.checkpoint
  "実行中の状態を、プロセスが死んでも読める形にする。

  ## なぜ要るのか

  `kuro.stream` の値は in-memory にしかない。サーバが落ちれば「何が走って
  いたか」も「どこまで出力したか」も消える —— `kobo.agent` の durable loop は
  **checkpoint 無しにはこの上に載らない**（ADR-2606301000 が言う
  lease/tick → checkpoint → budget は、再開できる状態を前提にしている）。

  ## この名前空間の責務は変換だけ

  純 `.cljc`。ファイルにも DB にも書かない —— **書き込みは host の仕事**で、
  ここは `stream ⇄ EDN` の可逆変換と、途中で失われたものの申告を持つ。
  そうしておくと、保存先（ファイル / kotobase / DataLad）を選び直しても
  この層は変わらない。

  ## 復元は「同じ値」ではなく「正直な値」

  `restore` は保存時点の状態を返すが、**保存後に起きたことは知らない**。
  プロセスが死んだ時点で走っていた stream を復元しても、その子プロセスは
  もう居ない —— だから復元された stream は `:kuro/state :orphaned` になり、
  `running?` は偽を返す。`:running` のまま返すと、呼び出し側は生きていない
  プロセスに stdin を送ろうとする。

  `finish` は orphaned な stream にも使える（exit code が分からないので
  host が `{:exit-code 129 :error \"...\"}` のような値を入れる）—— 落ちた実行を
  receipt にして閉じられないと、台帳に開きっぱなしの穴が残る。"
  (:require [clojure.string :as str]
            [kuro.stream :as stream]))

(def format-version 1)

(defn- chunk->edn [c]
  (select-keys c [:stream :text :kuro/seq]))

(defn ->edn
  "stream を保存できる EDN 値にする。

  opts `:max-chunk-bytes` を渡すと本文をそこまでに切り詰め、切った量を
  `:kuro.checkpoint/dropped-bytes` に記録する（**黙って切らない**）。
  既定は切らない —— 切るかどうかは保存先の都合であって、この層の既定では
  ない。"
  ([st] (->edn st {}))
  ([st opts]
   (let [cap (:max-chunk-bytes opts)
         chunks (mapv chunk->edn (:kuro/chunks st))
         [chunks dropped]
         (if-not cap
           [chunks 0]
           (reduce (fn [[acc dropped] c]
                     (let [n (count (:text c))
                           room (max 0 (- cap (reduce + 0 (map (comp count :text) acc))))]
                       (cond
                         (<= n room) [(conj acc c) dropped]
                         (pos? room) [(conj acc (update c :text subs 0 room)) (+ dropped (- n room))]
                         :else [acc (+ dropped n)])))
                   [[] 0] chunks))]
     (cond-> {:kuro.checkpoint/version format-version
              :kuro.checkpoint/state (:kuro/state st)
              :kuro/session (:kuro/session st)
              :kuro/command (:kuro/command st)
              :kuro/session-id (:kuro/session-id st)
              :kuro/chunks chunks
              :kuro/seq (:kuro/seq st)
              :kuro/stdout-bytes (:kuro/stdout-bytes st)
              :kuro/stderr-bytes (:kuro/stderr-bytes st)
              :kuro/dropped-bytes (:kuro/dropped-bytes st)
              :kuro/truncated? (:kuro/truncated? st)
              :kuro/max-output-bytes (:kuro/max-output-bytes st)}
       (pos? dropped) (assoc :kuro.checkpoint/dropped-bytes dropped)))))

(defn restore
  "checkpoint から stream を戻す。

  **`:running` では戻さない。** 保存された実行のプロセスはもう居ないので、
  `:orphaned` にする —— `:running` のまま返すと、呼び出し側は生きていない
  プロセスに stdin を送ろうとする。"
  [cp]
  (when-not (= format-version (:kuro.checkpoint/version cp))
    (throw (ex-info "unknown checkpoint version"
                    {:got (:kuro.checkpoint/version cp) :expected format-version})))
  (let [was (:kuro.checkpoint/state cp)]
    {:kuro/type :kuro/stream
     :kuro/state (if (= :running was) :orphaned was)
     :kuro/restored-from was
     :kuro/session (:kuro/session cp)
     :kuro/command (:kuro/command cp)
     :kuro/session-id (:kuro/session-id cp)
     :kuro/chunks (vec (:kuro/chunks cp))
     :kuro/seq (:kuro/seq cp)
     :kuro/stdout-bytes (:kuro/stdout-bytes cp)
     :kuro/stderr-bytes (:kuro/stderr-bytes cp)
     :kuro/dropped-bytes (:kuro/dropped-bytes cp)
     :kuro/truncated? (:kuro/truncated? cp)
     :kuro/max-output-bytes (:kuro/max-output-bytes cp)}))

(defn orphaned?
  "保存時に走っていて、復元時にはもうプロセスが居ない stream か。"
  [st]
  (= :orphaned (:kuro/state st)))

(defn abandon
  "orphaned な実行を receipt にして閉じる。

  落ちた実行を閉じられないと台帳に開きっぱなしの穴が残る。exit code は
  **推測しない** —— host が知っている値（既定 129 = SIGHUP 相当）と理由を
  入れる。"
  ([st] (abandon st {}))
  ([st result]
   (when-not (contains? #{:running :orphaned} (:kuro/state st))
     (throw (ex-info "abandon closes a run whose process died — a stream with another state already has a real ending"
                     {:state (:kuro/state st)})))
   (stream/finish (assoc st :kuro/state :running)
                  (merge {:exit-code 129
                          :error "process did not survive its host"
                          :isolation :none}
                         result))))

(defn summary
  "checkpoint を人が読める 1 行に。運用でまず知りたいのはこれ。"
  [cp]
  (str (:kuro/session-id cp) " "
       (str/join " " (:kuro/argv (:kuro/command cp)))
       " [" (name (:kuro.checkpoint/state cp)) "] "
       (+ (:kuro/stdout-bytes cp 0) (:kuro/stderr-bytes cp 0)) "B"
       (when-let [d (:kuro.checkpoint/dropped-bytes cp)]
         (str " (checkpoint dropped " d "B)"))))
