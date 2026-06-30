(ns kuro.terminal
  (:require [clojure.string :as str]))

(def terminal-modes
  {:terminal-safe
   {:kuro/mode :terminal-safe
    :kuro/label "safe"
    :kuro/default-capabilities #{"repo/read" "tmp/write" "log/write"}
    :kuro/host? false}

   :terminal-build
   {:kuro/mode :terminal-build
    :kuro/label "build"
    :kuro/default-capabilities #{"repo/read" "repo/write" "tmp/write" "cache/read" "cache/write" "net/fetch" "log/write"}
    :kuro/host? false}

   :terminal-agent
   {:kuro/mode :terminal-agent
    :kuro/label "agent"
    :kuro/default-capabilities #{"repo/read" "tmp/write" "log/write" "agent/checkpoint"}
    :kuro/host? false}

   :terminal-host
   {:kuro/mode :terminal-host
    :kuro/label "host"
    :kuro/default-capabilities #{"host/shell"}
    :kuro/host? true}})

(defn mode? [x]
  (contains? terminal-modes x))

(defn command-argv? [argv]
  (and (vector? argv)
       (seq argv)
       (every? #(and (string? %) (not (str/blank? %))) argv)))

(defn command
  ([argv] (command argv {}))
  ([argv attrs]
   (when-not (command-argv? argv)
     (throw (ex-info "command argv must be a non-empty vector of non-blank strings"
                     {:argv argv})))
   (merge {:kuro/type :kuro/command
           :kuro/argv argv}
          attrs)))

(defn session
  ([id repo-root-cid mode] (session id repo-root-cid mode {}))
  ([id repo-root-cid mode attrs]
   (when-not (mode? mode)
     (throw (ex-info "unknown terminal mode" {:mode mode})))
   (let [spec (terminal-modes mode)
         signed? (:kuro/signed-opt-in? attrs)]
     (when (and (:kuro/host? spec) (not signed?))
       (throw (ex-info "terminal-host requires signed opt-in"
                       {:mode mode
                        :reason :signed-opt-in-required})))
     (merge {:kuro/type :kuro/session
             :kuro/session-id id
             :kuro/repo-root-cid repo-root-cid
             :kuro/mode mode
             :kuro/cwd "."
             :kuro/events []
             :kuro/grant {:capabilities (:kuro/default-capabilities spec)}}
            attrs))))

(defn effective-capabilities [sess]
  (set (get-in sess [:kuro/grant :capabilities] #{})))

(defn command-allowed? [sess required-capabilities]
  (let [granted (effective-capabilities sess)
        required (set required-capabilities)]
    (empty? (remove granted required))))

(defn denial [sess required-capabilities]
  (let [granted (effective-capabilities sess)
        missing (vec (sort (remove granted (set required-capabilities))))]
    (when (seq missing)
      {:kuro/allowed? false
       :kuro/reason :missing-capabilities
       :kuro/missing missing
       :kuro/session-id (:kuro/session-id sess)})))

(defn append-event [sess event]
  (update sess :kuro/events conj event))

(defn receipt
  "Build a terminal receipt from host-supplied result data.

  This function does not run a command and does not compute cryptographic hashes.
  The host may include digest fields such as :stdout-cid, :stderr-cid, or
  :patch-cids in result."
  [sess cmd result]
  (let [exit-code (:exit-code result)]
    (when-not (integer? exit-code)
      (throw (ex-info "receipt result requires integer :exit-code" {:result result})))
    (merge {:kuro/type :kuro/receipt
            :kuro/session-id (:kuro/session-id sess)
            :kuro/repo-root-cid (:kuro/repo-root-cid sess)
            :kuro/mode (:kuro/mode sess)
            :kuro/cwd (:kuro/cwd sess)
            :kuro/argv (:kuro/argv cmd)
            :kuro/effective-capabilities (effective-capabilities sess)
            :kuro/exit-code exit-code}
           (select-keys result [:stdout :stderr :stdout-cid :stderr-cid :patch-cids :started-at :finished-at]))))

(defn receipt-fact [receipt]
  {:kotoba/type :kuro/terminal-receipt
   :kotoba/id [:kuro/receipt (:kuro/session-id receipt) (:kuro/argv receipt)]
   :kuro/receipt receipt})
