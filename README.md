# kuro

`kuro` is the Kotoba terminal model for `kobo`.

It is portable Clojure/ClojureScript (`.cljc`) and contains no direct shell,
PTY, filesystem, network, thread, or clock access. A host provides those effects
through aiueos capabilities; `kuro` defines the data contract that makes terminal
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
aiueos surface provider. `kuro` only records the intent and verifies the portable
shape.

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

## Tests

```sh
clojure -M:test
```
