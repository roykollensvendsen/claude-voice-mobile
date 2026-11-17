# The agent's parts are built in one place, with one log

The red runs are `red-phone-agent.txt` (`PhoneAgent` did not exist yet) and
`red-suggested-machine.txt` (`suggestedMachine` did not exist yet).

`PhoneAgent` builds the agent once: the pairing, the MCP handler with what the phone offers,
and the listener that follows the tailnet. For each address it builds a new gate from the
kept hub, a new endpoint and an HTTP server. The app gives it `calls`, the log its screen
reads, and `hub`, the file from #99.

This follows claude-voice-mobile-architect's review of the plan:
- **One MCP handler for the agent's life**, so the call ids it keeps survive a rebind. Test: a
  call that ran late, then Tailscale off and on at a new address, then the hub's retry of the
  same id is a `duplicate`, and the log reads `late, duplicate`.
- **`agent_info` gives the bare IPv4** (`100.64.0.2`), the paired hub and the machine name.
- **The machine name is suggested from the phone's model**, and the owner confirms it once the
  pairing screen exists. `suggestedMachine` makes a name the protocol takes, or `phone`.

The other tests:
- a call the hub makes shows up in the same `CallLog` the app holds;
- the agent moves when the address changes, and stops the old server first;
- the server lets in tailnet callers only, and once paired, only the hub;
- before pairing, the refusal names the phone's own machine to approve;
- a pairing kept before is used by a new agent at once;
- with the app's own parts, a bind that fails says why;
- the real HTTP server, bound to loopback for the test: a caller off the tailnet gets no answer,
  and once the agent stops, the port is closed.

**Pitest.** On `:core`, it caught all but main's two old mutants and one new one: the real
server's clock, `System::nanoTime`, replaced with `0L`. The clock is read only while a request
is being read, and the real server lets no one in from a test machine, since it has no tailnet
address. The clock's use is tested in `HttpServerTest` with a clock of its own. The PR body
carries the ratchet exception for it.

**One Pitest run failed** with no surviving mutant named. The next two runs passed, and the
core tests passed five times in a row with `--rerun`, so I could not reproduce it.
