# The phone's MCP handler

What this PR proves, and how. The red run is `red-mcp-handler.txt`.

## Decided with the app's architect (17.11)

- Bad JSON or a batch gets HTTP `400` with a JSON-RPC error and no id, as MCP's HTTP transport
  says. A wrong method or wrong parameters get `200` with a JSON-RPC error.
- A notification gets `202` with an empty body. It keeps `Content-Type: application/json` and
  `Content-Length: 0`, as ADR-CVM-005 says every answer does.
- A call id is claimed when the call is about to run: after its tool is found and its arguments
  read, before the plugin starts. A send refused before that never ran, so its retry runs.
- Each plugin and tool name is checked against agent-protocol's rule when the plugins are
  registered, and two of one name stop the app at start-up.
- No new ADR. These are implementation choices behind agent-protocol v1's MCP section and
  ADR-CVM-005's server:
  - **Call ids** are kept ten minutes, at most 10 000. When the store is full of younger ones, a
    call is refused as busy rather than an id forgotten early. With four connections the cap is
    out of reach.
  - **The time limit is 20 s**, under the hub's 30 s, so the phone answers first. Past it the
    answer says the call may still finish, and the id stays kept, so a retry cannot run it twice.
  - **No coroutines library in :core.** A plugin's suspending call starts with the standard
    library's `startCoroutine` on four daemon threads of its own, as many as the server takes
    connections. A call past the limit cannot be cancelled, only abandoned.

## Tests that prove more than they seem to

- **Suspended calls give their threads back.** Eight calls of 1.5 s each run under a 2.5 s
  limit on four threads, and all finish. As a check of the test itself, the plugin was changed
  to block its thread for the 1.5 s instead of suspending. The test then failed: the second four
  started late and ran past the limit. A first version with 600 ms calls under a 1 s limit left
  too little to spare: a full measuring run once failed under load, so the margin is now a
  second.
- **A plugin resumed on another thread**, with an answer or with an exception, is answered as
  usual or as broken. This covers the path where a call really suspends.
- **The ten minutes are exact:** an id is refused one millisecond before ten minutes and runs
  again at ten.

## Mutants

Pitest on `:core`: 445 made, 443 caught. The two left are main's: `Tailnet.kt` and `KeptGate.kt`.

Three kinds of mutant that no test can catch were designed out, not excluded:

- **The suspending lambda.** Kotlin writes a state machine for `suspend { plugin.call(...) }`,
  with a check for a failed start at each step. Neither check can change the outcome: a call is
  never started with a failure, and a failure that skips the check is still passed on as one,
  because a failure value returned as a result is wrapped again as a failure. The call is now
  started through a reference to a named function whose body is a tail call, which has no state
  machine.
- **The JSON builders' lambdas**, whose return value nothing reads. Answers are built as plain
  objects instead.
- **A `when` over the method's name**, which Kotlin compiles to a hash switch, with an equality
  check only a hash collision could reach. The methods are a map instead.
