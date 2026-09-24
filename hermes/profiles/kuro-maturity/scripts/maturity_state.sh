#!/bin/bash
# Deterministic maturity signal for kotoba-lang/kuro (timestamp-free).
KURO=~/github/com-junkawasaki/orgs/kotoba-lang/kuro
echo "=== TESTS (kuro) ==="
cd "$KURO" || { echo "kuro repo missing"; exit 0; }
if command -v clojure >/dev/null; then
  if clojure -M:test >/tmp/kuro_jvm.log 2>&1; then echo "jvm: PASS"; else echo "jvm: FAIL"; grep -m2 -E 'ERROR|FAIL|Exception' /tmp/kuro_jvm.log; fi
else echo "jvm: clojure CLI not found"; fi
if command -v nbb >/dev/null || [ -x node_modules/.bin/nbb ]; then
  if npm run --silent test:parity >/tmp/kuro_parity.log 2>&1; then echo "parity: PASS"; else echo "parity: FAIL"; grep -m2 -E 'ERROR|FAIL|Exception' /tmp/kuro_parity.log; fi
  if npm run --silent test:host >/tmp/kuro_host.log 2>&1; then echo "host: PASS"; else echo "host: FAIL"; grep -m2 -E 'ERROR|FAIL|Exception' /tmp/kuro_host.log; fi
else echo "nbb: not installed (npm install first)"; fi
echo "=== CI (kotoba-lang/kuro) ==="
gh run list --repo kotoba-lang/kuro --limit 5 --json conclusion,name,headBranch 2>/dev/null \
  | python3 -c 'import json,sys
try:
  d=json.load(sys.stdin)
  for r in d: print(r.get("conclusion"), r.get("name"), "|", r.get("headBranch",""))
except Exception as e: print("gh: unavailable")'
echo "=== CONFORMANCE COVERAGE ==="
# README enforce/not-enforce 表の性質ごとに、実装語彙へマップして grep する。
# 生のキーワード (select-keys, cancel) は test の語彙と一致せず偽陰性を出した
# (2026-09-03: 実際には terminal_test/receipt-drops-undeclared-result-keys と
#  stream_node_test/kill-stops-a-long-running-command がカバー済みだった)。
for pair in "isolation:isolation" "receipt:receipt" "grant:grant" \
            "select-keys:undeclared" "TERM:TERM" "checkpoint:checkpoint" \
            "ansi:ansi" "stream:stream" "cancel:kill" "stdin:stdin"; do
  kw="${pair%%:*}"; term="${pair##*:}"
  n=$(grep -rli "$term" test/kuro/ 2>/dev/null | wc -l | tr -d ' ')
  echo "$kw: $n test file(s) [grep: $term]"
done
echo "=== GIT STATE ==="
git -C "$KURO" log --oneline -3
git -C "$KURO" status --porcelain | head -5
