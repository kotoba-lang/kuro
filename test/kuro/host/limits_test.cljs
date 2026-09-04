(ns kuro.host.limits-test
  "The README enforce table names specific defaults: `:timeout-ms` default
  120 s and `:max-output-bytes` default 1 MiB. Those defaults are part of the
  contract a caller reads — a silent change makes the table lie. The existing
  deadline/output-cap tests only exercise explicitly-passed values, so this
  suite pins the defaults themselves and is shared by both Node providers."
  (:require [clojure.test :refer [deftest is testing]]
            [kuro.host.node :as node]
            [kuro.stream :as stream]
            [kuro.terminal :as t]))

(deftest sync-provider-defaults-match-the-table
  (testing "README: bounded time (`:timeout-ms`, default 120 s) and bounded
            output (`:max-output-bytes`, default 1 MiB) — the defaults are in
            the table, so changing them is a documented change"
    (is (= 120000 (node/default-timeout-ms)))
    (is (= 1048576 (node/default-max-output-bytes)))))

(deftest streaming-provider-shares-the-defaults
  (testing "one table governs both providers: the streaming path must not
            drift to its own limits — its timeout default is stated inline,
            its output cap comes from `kuro.stream`"
    (is (= 120000 (node/default-timeout-ms)))
    (is (= stream/default-max-output-bytes (node/default-max-output-bytes)))))

(deftest stream-uses-the-same-output-default
  (testing "`kuro.stream` caps at 1 MiB unless told otherwise, so the cap the
            streaming provider inherits is the cap the table states"
    (is (= 1048576 stream/default-max-output-bytes))
    (let [sess (t/session "s" "cid:repo" :terminal-repo)
          cmd  (t/command ["true"])
          st   (stream/open sess cmd)]
      (is (= 1048576 (:kuro/max-output-bytes st))))))
