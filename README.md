# kuro

`kuro` is the Kotoba **terminal** model for [`kobo`](https://github.com/kotoba-lang/kobo)
— the interactive terminal / console layer of the Kotoba workbench
(ADR-2606301000). If you are looking for a terminal, a console, a shell
session, a TTY surface, or command execution with an audit trail in this
workspace, this is the repository. The name carries none of that, which is why
this paragraph does.

`kuro.terminal` is portable Clojure/ClojureScript (`.cljc`) and contains no
direct shell, PTY, filesystem, network, thread, or clock access. A host
provides those effects; `kuro` defines the data contract that makes terminal
sessions auditable.

```text
kuro = terminal session + command intent + effective grant + receipt
```

**Status: R2** — the model is stable and a real backing runs commands with
streaming output, stdin and cancellation. Not a PTY (see *Not a PTY* below).

## Model

Terminal modes are explicit:

| mode | purpose |
|---|---|
| `:terminal-safe` | default sandbox: repo read, tmp write, no secrets |
| `:terminal-build` | build sandbox: repo read/write, cache, bounded net |
| `:terminal-agent` | durable agent tick terminal |
| `:terminal-host` | signed opt-in escape hatch |

The host may implement the backing with a local sandbox, container, microVM, or
aiueos surface provider. `kuro.terminal` only records the intent and verifies
the portable shape.

A receipt is uniformly `:kuro/*`. Host-supplied result keys are namespaced on
the way in and keys outside the declared set are dropped, so one host cannot
widen the shape another host will not produce.

## Example

```clojure
(require '[kuro.terminal :as t])

(def session
  (t/session "s1" "repo-cid" :terminal-safe
             {:kuro/cwd "."
              :kuro/grant {:capabilities #{"repo/read" "tmp/write"}}}))

(def cmd (t/command ["clojure" "-M:test"]))

(t/receipt session cmd {:exit-code 0 :stdout "ok\n" :stderr ""})
```

## Running commands for real — `kuro.host.node`

`kuro.host.node` is the Node (nbb) host provider for one-shot commands. It is ClojureScript, not `.cljc`, because the effect belongs to
the host and the model stays portable without it.

```clojure
(require '[kuro.host.node :as host])

(host/run session (t/command ["git" "status" "--short"]) {:repo-root "."})
;; => {:kuro/type :kuro/receipt :kuro/exit-code 0
;;     :kuro/stdout "…" :kuro/stdout-cid "bafkrei…" :kuro/duration-ms 41 …}
```

### What it enforces

| guarantee | mechanism |
|---|---|
| denial before execution | missing capability ⇒ denial map, no spawn |
| no shell interpolation | argv vector, `:shell false` |
| cwd confinement | resolved path must stay under the repo root |
| no ambient environment | the child env is the declared manifest; `process.env` is never passed |
| bounded time | `:timeout-ms`, default 120 s ⇒ exit 124, `:kuro/timed-out?` |
| bounded output | `:max-output-bytes`, default 1 MiB ⇒ exit 125, `:kuro/truncated?` |
| content-addressed output | stdout/stderr as CIDv1-raw/sha2-256 (`bafkrei…`) |

### What it does not enforce

A capability set is an **intent record, not a kernel.** This provider does not
confine filesystem writes, does not block network access, and does not isolate
the process namespace. A command granted only `repo/read` can still write to
disk; nothing stops it but the receipt saying it should not have.

That is the documented split — the backing may be a sandbox, container, microVM,
or aiueos surface provider. This is the smallest honest backing: enough to
produce real receipts from real commands, not enough to run untrusted code.
`:terminal-host` has no backing here at all and is refused.

One measured caveat: on macOS, CoreFoundation injects
`__CF_USER_TEXT_ENCODING` into every child below the spawn API. The manifest is
everything this provider passes, not literally everything the child sees.

## Streaming, stdin, cancellation — `kuro.host.stream-node`

`run` is synchronous: it returns only when the command is over, which makes a
ten-minute build and an infinite loop look identical to the caller. The
streaming provider fixes that.

```clojure
(require '[kuro.host.stream-node :as sh])

(def h (sh/start session (t/command ["npm" "test"])
                 {:repo-root "."
                  :on-chunk (fn [st chunk] (print (:text chunk)))
                  :on-exit  (fn [receipt] ...)}))

((:write h) "y\n")      ; stdin
((:close-stdin h))
((:kill h))              ; SIGTERM
@(:stream h)             ; the live kuro.stream value, readable at any moment
```

`sh/run-async` wraps it in a Promise resolving to the receipt.

The same guarantees apply — capability check before spawn, argv with no shell,
cwd confinement, declared environment, deadline, output cap. The cap is
enforced across **both** streams together by `kuro.stream`, which also records
how many bytes it dropped: a silently-cut receipt is indistinguishable from a
short success.

## Reading real output — `kuro.ansi`

Command output is not plain text. `kuro.ansi` turns it into styled lines.

```clojure
(ansi/lines "\u001b[32mok\u001b[0m\nnext")
;; => [[{:text "ok" :style {:fg "green"}}] [{:text "next" :style {}}]]
```

Handled: SGR (bold/dim/italic/underline/inverse/strike, 8 + bright + 256 +
truecolor, resets), `\r` in-line overwrite, `\b`, tab expansion, and `CSI K`
erase — which is what actually collapses a progress bar (a bare `\r` leaves
the tail, on a real tty too). Everything else — cursor addressing, scroll
regions, alternate screen — is **discarded**, never printed.

### Not a PTY

The child is connected to **pipes**, not a pseudo-terminal. `isatty` is false,
so many programs disable colour and switch to line buffering; full-screen
programs (`vim`, `top`) do not work; there is no terminal size and no
SIGWINCH. The declared environment sets `TERM=dumb` rather than lying about
it.

`kuro.ansi` is a line-oriented reader, not a screen emulator — it has no
cursor grid and no scrollback. A real PTY needs a native addon (node-pty or
equivalent), which is a dependency decision this repo has not taken.

## Tests

```sh
clojure -M:test                     # portable model + ansi + stream (JVM)
npm install && npm run test:host    # both Node host providers (nbb)
```

Both run in CI. The first proves the model stays portable; the second proves a
receipt describes a command that really ran.
