# Streaming and saved browser conversations

The browser profile now supports the original Hermes `_StreamingCall` SSE
accumulator and OpenAI SDK streaming parser. It pulls raw byte chunks through
bounded Worker RPC, preserving split UTF-8 and fragmented tool arguments.
`onDelta` receives visible text while the upstream connection is still open;
`onReasoning` receives Hermes reasoning callbacks separately. Hermes remains
synchronous inside its Worker; browser fetch and the UI remain asynchronous.

## Reproduce and use

```sh
npm run build:browser-tools
npm run prepare:hermes-browser -- /path/to/hermes-agent
npm run test:hermes-streaming
```

```js
import {createHermesBrowser} from './hermes.js';
const agent = createHermesBrowser({
  model: 'your-model', modelEndpoint: '/your-chat-completions-proxy',
  capabilities: ['tool/js', 'tool/python', 'tool/python-js-bridge',
                 'network/model', 'session/persist']
});
const result = await agent.run({sessionId: 'my-conversation', prompt: 'Calculate 42'},
  {signal: abortController.signal, onDelta: text => display(text)});
const saved = await agent.load('my-conversation');
// After reload/restart, use the same model, endpoint, toolset and sessionId.
await agent.run({sessionId: 'my-conversation', prompt: 'What did we calculate?'});
```

`streaming` defaults to true; explicitly set false for JSON-only endpoints.
The bundled `hermes.html` is a small interface for send, stop, and saved-history
inspection; configure a usable endpoint. `/live-model` is the verification
relay path, not a deployed production endpoint.

## Commit and restart semantics

IndexedDB stores the conversation messages, tool results/receipts, exact cached
system prompt, session id, runtime identity and monotonically increasing
revision. It never stores `modelHeaders` or provider credentials. The host awaits
an IndexedDB transaction completion with strict durability before reporting
`saved: true`. Browser storage can still be evicted or explicitly cleared; this
is browser-local conversation persistence, not cloud backup or the full native
Hermes SQLite session DB.

Only completed, successful, non-partial turns commit. Worker termination,
model failures and cancellation retain the previous committed turn. In-flight
tool actions are not automatically replayed after a crash. Web Locks reject a
second simultaneous turn for one session; an atomic IndexedDB revision check
also prevents stale writers. Model/endpoint/toolset/profile mismatches reject
resume before contacting the model. Saved incomplete tool rounds are rejected.

The test actually closes Chromium and relaunches a persistent test profile,
then runs a second Hermes conversation turn from the saved snapshot. It verifies
stable system-prompt bytes, the original message/receipt history, revision 1→2,
fragmented SSE and tool arguments, token usage, cancellation while reading SSE,
concurrent-turn denial, identity mismatch and revision conflict. The server
waits for an acknowledgement from `onDelta` before ending the first text stream,
so receiving deltas only after completion cannot pass.

Evidence: `hermes-browser-streaming.json`. This test uses a model fixture and
does not certify live inference.

## Real-model qualification

```sh
npm run test:hermes-live -- /path/to/native/hermes-agent mishima
```

The native checkout's `venv/bin/python` resolves its configured custom-provider
credentials and relays the actual HTTP/SSE response. Credentials stay in that
native process and are not emitted, serialized into the browser Worker, or
stored in IndexedDB. The relay binds only localhost and accepts same-origin
POSTs for the selected model. It is a local verification helper, not a production
credential proxy. Abort closes the upstream process/request.

The live test uses the actual model's tool selection and results and passes
only after real JS/Python round trips, streaming deltas and a completed turn.
`hermes-browser-live.json` records the observed result, including upstream
failure status; failed qualification is not converted to a mock success.

Observed live attempt on 2026-10-04: authenticated browser→local relay→
`https://api.murakumo.cloud/v1/chat/completions` with model `mishima` returned
HTTP 502, `mishima_unreachable` / `destination_unavailable` on every bounded
attempt. The outer browser deadline stopped the turn; no live model/tool round
trip or saved live conversation was achieved. The catalog's `murakumo/free`
route returned the same upstream failure. A Qwen catalog route returned 401
`invalid_credential`; localhost Ollama/LM Studio endpoints were unavailable.
No credential or infrastructure configuration was changed. A reachable,
authorized model route is required to finish live qualification.
