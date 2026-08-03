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

`kuro.host.node` is the Node (nbb) host provider: the only place a command
actually runs. It is ClojureScript, not `.cljc`, because the effect belongs to
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

### Not a PTY

There is no pseudo-terminal, no ANSI/VT escape handling, no line editing, and
no input loop. `run` is one command in, one receipt out. Interactive editing
and streaming output are unbuilt.

## Tests

```sh
clojure -M:test                                    # portable model (JVM)
npm install && npm run test:host                   # Node host provider (nbb)
```

Both run in CI. The first proves the model stays portable; the second proves a
receipt describes a command that really ran.
