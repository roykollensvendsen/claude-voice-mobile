# The hub's name is cut to its first label

The red run is `red-hub-name.txt`: `hubName` did not exist yet.

voice-architect's note on #101: the phone kept the hub's full MagicDNS name, such as
`laptop-a.tail1234.ts.net`, from a reverse lookup of its address. The laptop's own agent keeps
the first label, `laptop-a`, from `tailnet.whois` (ComputedName, or the first label of Name). So
the two would report the same hub differently. `hubName()` now cuts the lookup to its first
label. A lookup that only gives the address back, as without MagicDNS, or gives nothing, is
still no name, and pairing says to turn on MagicDNS.

The tests:
- a full name, one with a trailing dot, and a short one all give `laptop-a`;
- the address itself, an empty name, `.` and a name with an empty first label give no name.

Pitest on `:core` caught all but main's two old mutants, and the ratchet holds.
