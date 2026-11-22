# Decision records

A decision that costs more to reverse than to take is written down here before
the code that rests on it. A decision that only implements one already taken
is not; it is a commit message.

## The form

Every record is `ADR-CVM-NNN-a-short-title.md`, with these sections in
this order:

| Section | Holds |
|---|---|
| Status | Accepted or Proposed, who decided, when; an objection returns it to Proposed |
| Context | what was true when the question arose, with sources where the facts are checkable |
| Options considered | every option weighed, including doing nothing, each with why it was not taken |
| Decision | what was chosen, in one paragraph |
| Consequences | what this costs, what it forecloses, and at least one thing that gets worse |
| Related | the pages it shapes |

Numbers are one sequence and never reused. A record is superseded by a later
one, never edited into a different decision.

Record the process choices too, not only the artefact ones. How a change is
made, and why, is what costs time on every future contribution, and it is the
decision nobody writes down.

## The records

| Id | Decides | Status |
|---|---|---|
| [ADR-CVM-001](ADR-CVM-001-a-native-android-app-speaking-the-hubs-protocol.md) | A native Android app, speaking the protocol claude-voice specifies | Proposed |
| [ADR-CVM-002](ADR-CVM-002-how-a-change-is-made.md) | How a change is made: owned parts, tests first, merged when green | Proposed |
| [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md) | The app's internal design: a core with plugin modules, one foreground service | Proposed |
| [ADR-CVM-004](ADR-CVM-004-measuring-the-apps-code-health.md) | Measuring the app's code health: Kover, detekt and Pitest, kept as CI artifacts | Proposed |
| [ADR-CVM-005](ADR-CVM-005-a-small-http-server-of-the-apps-own.md) | A small HTTP/1.1 server of the app's own in the core, on the tailnet address alone | Proposed |
| [ADR-CVM-006](ADR-CVM-006-an-estimate-of-the-mobile-data-left.md) | An estimate of the mobile data left, from the phone's own count, in a plugin of its own | Proposed |
| [ADR-CVM-007](ADR-CVM-007-modules-each-install-picks.md) | Modules each install picks when built and switches at run time, and capabilities below the plugins | Proposed |
| [ADR-CVM-008](ADR-CVM-008-a-microphone-mute-that-holds.md) | A microphone mute that holds: measured first, then a capability with headset adapters | Proposed |
| [ADR-CVM-009](ADR-CVM-009-how-the-phone-knows-its-hub.md) | How the phone knows its hub: the address and key kept at pairing, checked on every request | Decided |
| [ADR-CVM-010](ADR-CVM-010-reading-and-sending-text-messages.md) | Reading and sending text messages: a masked queue the hub pulls, and drafts with an undo delay | Proposed |
| [ADR-CVM-011](ADR-CVM-011-saying-what-the-phone-allows-now.md) | Saying what the phone allows now: an `access` state per use, read fresh, never asking | Proposed |

What is deliberately left undone, and what would make each worth doing, is
[`deferred.md`](deferred.md). A thing left undone with no trigger is a thing
forgotten.
