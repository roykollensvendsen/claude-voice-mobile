# A plugin may say it uses `messages`

The red run is `red-uses-messages.txt`: a plugin that said it uses `messages` was refused.

This app accepts only the names the protocol lists in a plugin's `uses` (ADR-CVM-003; #95). The
`sms` and `sms_send` plugins of ADR-CVM-010 need `messages`, "reads or sends the owner's text
messages". claude-voice's `docs/agent-protocol/next-version.md` now lists it (claude-voice #340).
So it is added to the list, which now says why a name beyond v1's nine may be there: only once
the protocol lists it.

A v1 hub already carries the name, since v1's `uses` is open, and shows it as one it does not
know. No plugin uses it yet; the `sms` plugin comes next.
