# ADR-CVM-005: A small HTTP server of the app's own

## Status

Proposed 2026-11-15 by the architect session, at the Android developer's
question, relayed by the manager session. Settled when the owner merges it.

## Context

- The phone is an agent: the hub calls it over the tailnet, at `POST /pair` and
  at `POST /mcp`. The protocol is claude-voice's `docs/agent-protocol.md`,
  version 1.
- That protocol lets the agent answer in plain JSON and keep no MCP session. The
  phone needs no stream of events, no long-lived connection and no HTTP/2.
- The gate in `:core` checks the bearer key, the caller's address, `Host` and
  `Origin`, and the path. It has to see the request target exactly as it was
  sent. A server that decodes or tidies the path first, so that `/%6Dcp` or
  `/mcp/` reaches the gate as `/mcp`, lets a request past a check it should
  have failed.
- Nothing listens on the phone yet. No record says what will.

## Options considered

- **Ktor's CIO engine.** It is well kept, but brings coroutines and a large tree
  of dependencies into `:core`. It would have to be made never to normalise the
  path, and that guarantee would be ours to watch at every upgrade. It does much
  more than the phone needs. Not taken.
- **NanoHTTPD.** It is small, but unmaintained since 2016, and it decodes the
  URI before the handler sees it. That breaks the rule above. Not taken.
- **A small HTTP/1.1 server in `:core`, written here.** It needs no dependency,
  and the gate sees the raw target. Pitest measures it like the rest of the core.
  The price is a parser of our own, which is exactly where a server is attacked.
  So it ships only with a test set of hostile requests and a fuzz test.
  **Taken.**

## Decision

**`:core` serves HTTP itself, over a `ServerSocket`, with no library.** It uses
plain JVM types only, so it stays free of Android, and `:app`'s service only
starts and stops it.

**It listens on the tailnet address alone.** It binds to the phone's IPv4
tailnet address from `Tailnet.state`, never to every address. When that address
goes away, the listening socket and every open connection are closed. When the
address comes back, the server binds again.

- **IPv4 only.** The gate also accepts the tailnet's IPv6 range, so binding IPv6
  later changes no check, but the server listens on IPv4 alone for now.
- **Only the hub, once paired.** Once the phone is paired, a connection from any
  address but the paired hub's is closed when it is accepted, before a byte is
  read. Before pairing, and while a pairing code is open, any tailnet address is
  read. This keeps the connection slots for the hub, and the gate still makes
  every check of its own.
- **No TLS.** The tailnet's WireGuard tunnel encrypts and authenticates every
  packet between the hub and the phone, so the server does not add TLS of its own.

**One request per connection, and then it closes.**

- The request line must end in `HTTP/1.1`. Anything else gets `505`.
- The method is matched in exact case: `POST`, not `post`.
- `POST` must carry exactly one `Content-Length`. Without it, the answer is `411`.
- Each answer carries `Content-Length`, `Content-Type: application/json` and
  `Connection: close`, and the server closes the connection after it. It never
  answers as a stream of events.
- A `GET` or `DELETE` on `/mcp` gets `405`, which the MCP transport allows.
- A request with an `Expect` header gets `417`. The hub never sends one.

**The target is passed to the gate byte for byte.** It is never decoded or
normalised, and a query string is not split off. Only `/pair` and `/mcp`, exactly
as written, reach a handler. Anything else gets `404`, including an absolute
target such as `http://phone-a/mcp` and the asterisk target `*`.

**Limits, each with a test:**

| What | Limit | Beyond it |
|---|---|---|
| The request line | 2 KiB | `414`, then close |
| All header lines together | 8 KiB, and at most 64 lines, counted as lines, not names | `431`, then close |
| The body | 256 KiB | `413`, then close |
| Time from accepting the connection to the end of the headers | 5 s | close, with no answer |
| Time to read the body | 10 s | close, with no answer |
| Time to write the answer | 10 s | close |
| Connections at once | 4 | a fifth is closed at once |

The time limits read an injected monotonic clock, which is `System.nanoTime` in
the app. A test moves the clock forward instead of waiting. The clock starts when
the connection is accepted, not at its first byte. Every accepted socket has a
read timeout of 1 s, so a client that connects and sends nothing still wakes the
reader, which checks the limit and closes. Writes cannot time out by themselves,
so a watchdog closes a socket whose answer is not written within the limit.

**Hostile requests get a fixed refusal, and the connection is closed.** Each row
below is a named test:

| The request | The answer |
|---|---|
| Two `Content-Length` headers, even with the same value | `400` |
| Any `Transfer-Encoding`, with or without `Content-Length` | `400` |
| No `Host` header | `400` |
| Any header name twice, such as `Host`, `Origin` or `Authorization`, whatever its case | `400` |
| A line ending in a bare LF, or a bare CR anywhere | `400` |
| A header line that folds onto the next, or has space before its colon | `400` |
| A header name that is not an RFC 9110 token | `400` |
| A control byte in a header value, other than a tab | `400` |
| A byte outside printable ASCII in the target | `400` |
| A `Content-Length` that is not plain digits, or overflows | `400` |
| A body shorter than its `Content-Length` when the peer stops | close, nothing handled |
| A header sent a byte at a time past the time limit (slowloris) | close, nothing handled |

The hub never sends a header twice. So no header the gate reads can arrive
twice, with the parser taking one copy and the gate the other.

Bytes after the body are never parsed. The answer is written, and the
connection is closed.

Every refusal body is claude-voice's `error.json`. A refused request never
reaches the gate, and never reaches a plugin.

**A fuzz test over the parser runs in CI.** It is a plain JUnit 5 test with a fixed
seed. It takes the valid requests and the hostile set above, and mutates them:
bytes flipped, inserted, cut and repeated. It runs a few thousand cases in
seconds. For every input the parser must do one of two things: give back a
request within the limits, or give one of the fixed refusals. It must never throw
anything else and never hang. A case it finds is added to the named set.

Once a week a scheduled CI run does the same with a fresh seed and more cases. It
prints the seed, so a failure can be run again exactly.

## Consequences

- `:core` gains no dependency, and the framing rules are ours to read and test.
- The parser is the most exposed code in the app, so Pitest covers it with the rest
  of `:core`. A surviving mutant in it is treated as a missing test.
- Keeping one request per connection costs a TCP handshake on every call. On the
  tailnet that is a few milliseconds, against a voice turn of seconds.
- If a later protocol version needs a stream, a kept connection or the phone
  dialling the hub (in claude-voice's `docs/agent-protocol/next-version.md`), this
  record is reopened. Ktor is weighed again then, against what that version needs.
- Coverage-guided fuzzing with Jazzer is not done now. It is a row in
  [`deferred.md`](deferred.md).

## Related

- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): the core, the tailnet
  listener and the foreground service that starts it.
- [claude-voice `docs/agent-protocol.md`](https://github.com/roykollensvendsen/claude-voice/blob/main/docs/agent-protocol.md):
  version 1, the pairing, the gate and the MCP transport.
