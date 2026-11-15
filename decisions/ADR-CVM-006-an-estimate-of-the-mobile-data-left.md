# ADR-CVM-006: An estimate of the mobile data left

## Status

Proposed 2026-11-15 by the architect session, at the owner's request, relayed
by the manager session. Reviewed by the quality and voice architect sessions,
then shown to the owner before anything is built. Settled when the owner
merges it.

## Context

- The owner wants to ask the voice how much mobile data is left this month.
- The carrier knows the exact figure, but reading it means logging in to the
  carrier. How that could be done safely, if at all, is still being researched.
  That is "route a", and it is not decided here.
- The phone itself counts the bytes it sends and receives over mobile data.
  Android's `NetworkStatsManager` gives that count for any span of time. With a
  quota and a renewal day, the phone can work out an estimate of what is left,
  with no login and no secret. That is "route b", decided here.
- Protocol version 1 already has the private thing this reaches: `usage`, "the
  use of the machine and its apps, such as mobile data used".

## Options considered

- **Wait for route a.** That gives the exact figure, but needs a carrier login
  that is not yet known to be safe, and may never be. The owner asked for
  something now. Not taken.
- **A tool in the `status` plugin.** That is less code. But then `status`
  would use `usage`, and the owner would approve that private thing for the
  battery level too. Not taken.
- **A plugin of its own, `mobiledata`, reading the phone's own count.** It is
  read only, holds no secret, and its `uses` says what it reaches. **Taken.**

## Decision

**A plugin `mobiledata`, in `plugins/mobiledata`.** It depends only on
`plugin-api`, and its `uses` is `["usage"]`. It reads Android's
`NetworkStatsManager` itself. The core hands it nothing.

**One tool, `mobiledata_left`, which takes no input.** It answers with
`structuredContent`:

| Field | What it is |
|---|---|
| `used_bytes` | mobile bytes sent and received since the period began |
| `quota_bytes` | the quota the owner entered |
| `left_bytes` | the quota less the used bytes, never below zero |
| `period_start`, `period_end` | the renewal day this month and the next, in the phone's time zone, as ISO 8601 dates |
| `source` | `estimate` |
| `say` | for example "About 6.2 GB of 20 GB left, until 1 December. This is the phone's own count, so it is approximate." |

The words always say "about", and that the count is the phone's own. The voice
reads `say` as it is.

**The count.** It is `querySummaryForDevice` for mobile data, from the start of
the period until now. The subscriber id is passed as null, which Android allows
since version 10 and which counts every mobile network on the phone. It is not
exact, and the record says so plainly:

- it does not see add-on packs, or data the carrier does not count against the
  quota (zero-rated data);
- it does not see another SIM or device that shares the same plan;
- with two SIMs, it counts both;
- it counts the data the phone shares as a hotspot, and roaming, whether or not
  the plan counts those;
- Android stores the count in buckets, so the latest use may show up late. How
  late is measured on the phone, before the words promise anything.

**What the owner sets, once, on the phone.** The owner enters the
quota in GB and the renewal day, 1 to 31, in the app's own screen. A day the
month does not have means the month's last day. These are phone-only settings, like the ones in
ADR-CVM-003. The tool takes no input, so nothing the hub sends can change them,
and a test proves that. Until they are set, the tool refuses, with words saying
what to set and where.

**One permission, given by hand.** Reading the count needs `PACKAGE_USAGE_STATS`,
which Android grants only in Settings, under usage access. The app's screen has
a button that opens that page. When access is not given, the tool refuses with
words saying where to give it. It never asks for any other permission.

**A seam for the exact figure.** The plugin reads through one interface,
`DataLeftSource`, which gives back the used, quota and left bytes, the period and
a `source`. The phone's count is one implementation, and a later carrier source
would be another. The tool and its words do not change, except that `source`
says which one answered, and "about" is dropped only for an exact source.
Whether route a is built at all, and how its login is kept, is a record of its
own, with the owner's yes.

**What stays on the phone.** The plugin keeps the quota and the renewal day, and
nothing else. It sends the hub only the answer above. Like every plugin that
uses a private thing, the call log keeps the time and the caller of each call,
not its answer.

**Order.** After the earbuds plugin, as the next plugin in ADR-CVM-003's step 4.
It is small, and it needs no Bluetooth or playback work.

## Consequences

- The voice can answer "how much data is left?" without anyone logging in to
  the carrier, and it says the answer is approximate.
- The owner approves `usage` once for this plugin, and `status` stays without it.
- The estimate can be off by whatever the list above does not see. If it is off
  by enough to mislead, that is the trigger to look at route a again.
- `NetworkStatsManager` is read through a small interface of the plugin's own,
  `MobileBytes`. So plain JVM tests with a fake count cover the arithmetic: a
  renewal day on the 31st, a period across a new year, a time-zone change, use
  above the quota, and access not given.

## Related

- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): plugins, phone-only
  settings and the order they are built in.
- [claude-voice `docs/agent-protocol.md`](https://github.com/roykollensvendsen/claude-voice/blob/main/docs/agent-protocol.md):
  `uses`, and `usage` among its values.
