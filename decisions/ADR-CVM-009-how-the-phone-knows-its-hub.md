# ADR-CVM-009: How the phone knows a call is from its hub

## Status

Decided 2026-11-15 by the owner, who chose option a, relayed by the manager
session. Written by the architect session.

## Context

- Protocol version 1 has the agent ask Tailscale which machine is calling, and
  answer only its paired hub's node. The laptops do that.
- Android's Tailscale app answers no other app, so the phone cannot ask.
- A request must still pass two checks, the key and the machine. Neither alone
  is enough.

## Options considered

- **(a) Remember the hub at pairing.** The phone keeps the hub's tailnet
  address, its name as Tailscale gave it, and a hash of the key. Every request
  must come from that address and carry that key. Tailscale's WireGuard tunnel
  lets only the hub's node send from its address. **Taken.**
- **(b) Build Tailscale into the app**, so that it can ask who is calling as the
  laptops do. It would be a second tailnet node on the phone and a much larger
  build. Not taken.

## Decision

- **At pairing, the phone keeps three things:** the hub's tailnet address, its
  name, and a hash of the key. They survive a restart.
- **Every request is checked against the address and the key.** A request from
  any other address is refused, whatever key it carries. Once paired, the HTTP
  server closes a connection from any other address before reading it
  (ADR-CVM-005).
- **The name is kept to show to the owner,** not checked. Nothing is looked up
  by name, so no DNS answer can change what the phone trusts.
- **A change means pairing again.** If the hub's address changes, its requests
  are refused, and the app says to pair again. The phone never follows the hub
  to a new address by itself.
- **The check sits behind one interface, `CallerCheck`.** The pinned address is
  its one implementation. If Tailscale on Android ever offers a lookup of the
  caller, a second implementation can replace it, as the row in
  [`deferred.md`](deferred.md) says.

## Consequences

- The phone meets protocol version 1's two checks: the key, and a machine that
  Tailscale vouches for through its address.
- Moving the hub to a new machine, or a new tailnet address, takes one pairing.
- Protocol version 1 is frozen and says the agent asks Tailscale. A note for
  agents that cannot ask, such as this phone, belongs in claude-voice's list
  for the next version, and is the architect's to write there.

## Related

- [ADR-CVM-005](ADR-CVM-005-a-small-http-server-of-the-apps-own.md): the server,
  which closes other peers at accept once paired.
- [claude-voice `docs/agent-protocol.md`](https://github.com/roykollensvendsen/claude-voice/blob/main/docs/agent-protocol.md):
  pairing and the two checks on every request.
