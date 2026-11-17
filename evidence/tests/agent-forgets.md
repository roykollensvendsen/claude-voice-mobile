# The agent forgets the old address and gate once it listens nowhere

The red run is `red-agent-forgets.txt`.

claude-voice-mobile-architect's note (2) and quality's n1 on #101: when Tailscale went away, or
the agent was stopped, `PhoneAgent` kept the last address and gate, against its own comment. A
screen reading `problem` after the service ended would show an old pairing problem.

Now the address and its gate are kept together as one value. It is set just before a server
opens, so the first request already knows where it is. It is cleared when that server stops,
and when its bind fails. A move to a new address stops the old server first, so the old value
never clears the new one: the clear only takes the value it set.

The tests:
- with an unreadable pairing, `problem` is reported while listening, gone once Tailscale is
  off, back when it returns, and gone after `stop()`;
- with the app's own parts, a bind that fails reports no problem either.

Pitest on `:core` caught all but main's two old mutants and #101's clock mutant, and the
ratchet holds.
