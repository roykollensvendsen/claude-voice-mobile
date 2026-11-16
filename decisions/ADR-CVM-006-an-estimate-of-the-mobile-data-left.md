# ADR-CVM-006: An estimate of the mobile data left

## Status

Proposed 2026-11-15 by the architect session, at the owner's request, relayed
by the manager session. Reviewed by the quality and voice architect sessions,
then shown to the owner before anything is built. Settled when the owner
merges it.
Amended 2026-11-16, on the owner's answers relayed by the manager session: one
plan per phone, a second phone counts its own, and only made-up values appear
in the repository.

## Context

- The owner wants to ask the voice how much mobile data is left this month.
- The carrier knows the exact figure, but reading it means logging in to the
  carrier. That is "route a". The research found that the carrier's terms
  allow it to block a subscription used that way, and that its token would be
  a session for the whole account. So route a is most likely dropped.
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
| `over_bytes` | the used bytes beyond the quota, or zero |
| `period_start`, `period_end` | when this period began and when the next begins, as ISO 8601 times with their offset |
| `as_of` | the end of the latest count Android holds, as an ISO 8601 time |
| `partial` | true when the phone's count begins after `period_start` |
| `source` | `estimate` |
| `say` | for example "About 3.4 GB of 10 GB left, until the 9th. This is the phone's own count, so it is approximate." (made-up values) |

The words always say "about", and that the count is the phone's own. Over the
quota they say "about 1.3 GB over". When `partial` is true they say the count
began later than the period, and from when. The voice reads `say` as it is.

**A GB is 10^9 bytes**, as carriers bill, on the screen and in `say` alike. The
words give one decimal.

**The period.** It begins at 00:00 on the renewal day and ends at 00:00 on the
next one. Both are in the time zone saved with the settings, the phone's zone
when the owner saved them, so a trip abroad does not move the period. Saving
the settings again saves the zone again.

**The count.** It is `querySummaryForDevice` for mobile data, from the start of
the period until now. The subscriber id is passed as null, which Android allows
from version 10 (API 29) and which counts every mobile network on the phone.
The app supports Android 8 and up, so on Android 8 and 9 the tool refuses and
says it needs Android 10. The app's lowest version does not change for it.
It is not
exact, and the record says so plainly:

- the carrier's own count is the one that is billed. It may lag behind the
  phone's or differ from it, and when they disagree, the carrier is right;
- it does not see add-on packs, or data the carrier does not count against the
  quota (zero-rated data);
- it does not see another SIM or device that shares the same plan;
- with two SIMs, it counts both;
- it counts the data the phone shares as a hotspot, and roaming, whether or not
  the plan counts those;
- a phone that is new, reset or has had its data cleared counts only from
  then on. When its earliest count begins after `period_start`, the answer is
  marked `partial`, and its words say so;
- Android stores the count in buckets, so the latest use may show up late. How
  late is measured on the phone, before the words promise anything.

**What the owner sets, once, on the phone.** The owner enters the
quota in GB and the renewal day, 1 to 31, in the app's own screen. A day the
month does not have means the month's last day. These are phone-only
settings, like the ones in
ADR-CVM-003. The tool takes no input, so nothing the hub sends can change them,
and a test proves that. Until they are set, the tool refuses, with words saying
what to set and where.

**One permission, given by hand.** Reading the count needs `PACKAGE_USAGE_STATS`,
which Android grants only in Settings, under usage access. The app's screen has
a button that opens that page. When access is not given, the tool refuses with
words saying where to give it. It never asks for any other permission.

Usage access is given to the whole app, not to one plugin. What keeps the
other plugins away from it is a structure test: only `plugins/mobiledata` may
import `android.app.usage`, where `NetworkStatsManager` lives. The layer rules
already keep plugins from using one another, so no other plugin can reach the
count through this one.

**A seam for the exact figure.** The plugin reads through one interface,
`DataLeftSource`, which gives back the used, quota and left bytes, the period and
a `source`. The phone's count is one implementation, and a later carrier source
would be another. The tool and its words do not change, except that `source`
says which one answered, and "about" is dropped only for an exact source.
**No exact source is planned.** The seam costs one interface and keeps the door
open. Building route a would need a record of its own, and the owner's yes.

**What stays on the phone.** The plugin keeps the quota and the renewal day, and
nothing else. It sends the hub only the answer above. Like every plugin that
uses a private thing, the call log keeps the time and the caller of each call,
not its answer.

**One plan per phone, and a second phone counts its own.** The settings hold
one plan: a quota and a renewal day. A second phone with a plan of its own runs
its own copy of the app, paired as a machine of its own, and answers for its own
plan. The hub asks each phone, and the voice can name them or add them up. No
phone ever reports another phone's use. Two plans on one phone, one for each
SIM, are not built. An ordinary app cannot split Android's count by SIM: that
needs the subscriber id, which Android keeps from apps since version 10. The
settings are stored as a list of plans with one entry, so that a later way to
split by SIM would not change what is kept.

**Only made-up values in the repository.** The owner's quota, renewal day and
use are entered in the app and stay on the phone. No real quota, use or date
appears in the code, the tests, the examples, the pull requests or the
commits. Tests and examples use values that are plainly made up.

**Order.** After the earbuds plugin, as the next plugin in ADR-CVM-003's step 4.
It is small, and it needs no Bluetooth or playback work.

## Consequences

- The voice can answer "how much data is left?" without anyone logging in to
  the carrier, and it says the answer is approximate.
- The owner approves `usage` once for this plugin, and `status` stays without it.
- The estimate can be off by whatever the list above does not see. If it is off
  by enough to mislead, that is the trigger to look for an exact source again.
- `NetworkStatsManager` is read through a small interface of the plugin's own,
  `MobileBytes`. So plain JVM tests with a fake count cover the arithmetic: a
  renewal day on the 31st; periods of 28, 29, 30 and 31 days; a period across a
  new year; a time-zone change after the settings were saved; use above the
  quota; a partial count; Android 9; and access not given.

## Related

- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): plugins, phone-only
  settings and the order they are built in.
- [claude-voice `docs/agent-protocol.md`](https://github.com/roykollensvendsen/claude-voice/blob/main/docs/agent-protocol.md):
  `uses`, and `usage` among its values.
