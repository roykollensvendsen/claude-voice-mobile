# ADR-CVM-011: Saying what the phone allows now

## Status

Proposed 2026-11-22 by the architect session, at the owner's request relayed
by the voice and the manager session. It follows the line for the next
protocol version in claude-voice #442, which the voice architect and quality
sessions approved.

## Context

- Each plugin tells the hub what private things it may reach, its `uses`, in
  the `agent_info` answer. The owner approves that list. Nothing says whether
  Android allows each thing right now.
- The owner turned contacts off in the phone's settings. The hub still saw
  `contacts` under `sms_send`, so the voice could not warn before texting
  someone by name. The owner asked for this to work for every plugin, and for
  every machine.
- The next protocol version gives each plugin entry an `access` object
  (claude-voice, `docs/agent-protocol/next-version.md`). Version 1 already lets
  a newer sender add a field, and an older hub ignores it. So the phone can
  send it now, before version 2.
- One use can rest on several Android permissions. Reading texts needs
  `RECEIVE_SMS` and `READ_SMS`. Some uses are not runtime permissions at all.
  The data count, `usage`, is a setting the owner turns on in a settings page.
- Android tells "refused" from "never asked" only while the app asks. The app
  has to remember that it asked.
- Today the app asks for no plugin's permission. It asks only for
  notifications, at first start and from the notice line (ADR-CVM-003). The
  owner gives contacts and text messages in the phone's settings. So on the
  owner's phone today, every permission that is not granted is "never_asked".
- Android kills the app when the owner takes a permission away. It does not
  when the owner gives one back.

## Options considered

- **Do nothing.** The tool still refuses with `permission_missing` when it
  runs. The voice learns only after the owner has spoken the whole request.
  This is what the owner asked to change.
- **Drop a refused thing from `uses`.** Version 1 reads `uses` as what the
  plugin can reach. But `uses` is what the owner approved. Giving the
  permission back would make the list grow, and the hub would ask the owner
  to approve the plugin again. Rejected.
- **A read-only tool on the phone that answers the question.** It works within
  version 1. But a new tool needs the owner to approve the machine again, and
  the added field reaches the hub without that. The manager session dropped
  it.
- **An `access` field on each plugin entry.** Chosen.

## Decision

**Each plugin entry in `agent_info` carries `access`.** It has one key per
name in `uses`, and no other. Each value is "granted", "refused" or
"never_asked". It is read fresh in every `agent_info` answer, never cached. A
refusal changes `access`, never `uses`, so turning something back on needs no
new approval.

**A plugin names the permissions behind each use, and the core decides.** A
plugin says which Android permissions, or which special access, each of its
uses rests on. The state of each one comes from a small capability, which
reads `checkSelfPermission` or, for special access, the setting's own check.
The capability never calls `requestPermissions` and never opens a settings
page. Then a pure rule in the core turns those states into the use's
value. A use is granted only when every Android permission behind it is. If
one is refused and the app has asked for it before, the use is "refused".
Otherwise it is "never_asked". Pitest mutates the rule, and its tests cover
each of the three outcomes.

**The app remembers that it asked.** Every place in the app that asks for a
permission, or sends the owner to a special-access page, writes that down
first, in the app's no-backup storage. So "never_asked" means this install has
never asked. The record holds permission names only. Special access has no
request to refuse. It is "refused" when it is off and the app has sent the
owner to its page before, and "never_asked" when it is off otherwise.

**Plain states only.** `access` holds the three words and the use names it
already sends. It holds the states, never anything read with the access, such
as a contact or a number. The states are never logged, as with the rest of
`agent_info`.

**What the voice says.** This is the hub's part, written here so both sides
agree. Both "refused" and "never_asked" are fixed the same way, and the voice
says so: "turn on <the use> for claude-voice in the phone's settings". The
app does not ask for a plugin's permission when it opens, so "open the app"
would fix nothing. The two values stay apart for the hub, which may word them
differently once the app asks. That is a decision of its own, for a later
record. The tool's own answer stays the authority. A tool that finds its
permission missing still refuses with `permission_missing`.

## Consequences

- **Better:** the voice can warn before the owner speaks a whole request that
  cannot work. It can also answer "which permissions does the phone have?".
  This works the same way for every plugin, now and later.
- **Better:** turning a permission off and on again never asks the owner to
  approve a plugin again.
- **Worse:** late news. A permission given back shows only at the hub's next
  read, since Android does not restart the app for it. The hub fetches
  `agent_info` fresh for the owner's question, and again after any call to a
  tool it held as refused or never_asked. A warning from an older read is
  said as "last I heard".
- **Worse:** an install from before the record, a reinstall, and a permission
  given only in the settings all show "never_asked". So the value says less
  than its name. The spoken fix is the same for both values, so the owner
  still hears what works.
- **Worse:** a use that rests on several permissions shows "refused" when only
  one of them is. Where a precise warning matters, a plugin names its uses so
  that each tool needs one.
- Android's partial grants, such as approximate location, have no value yet.
  The hub treats a value it does not know, or a missing key, as unknown, and
  gives no warning. A later value such as "limited" breaks nothing.
- Tests:
  - the rule: all granted gives "granted"; one refused after asking gives
    "refused"; one not granted and never asked gives "never_asked";
  - `agent_info`: `access` has exactly the keys of `uses`, read on each call;
  - a source check: no file in the capability names `requestPermissions` or
    a settings action, so no path that builds `access` can ask;
  - every place that asks writes the record first;
  - an install from before the record: a permission not granted gives
    "never_asked", and nothing is asked;
  - on the emulator: turn contacts off in the settings, and the next
    `agent_info` says "never_asked" for `contacts` under `sms_send`.

## Related

- claude-voice, `docs/agent-protocol/next-version.md`: the `access` line.
- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): the asks at first
  start and from the notice line, which write the record.
- [ADR-CVM-007](ADR-CVM-007-modules-each-install-picks.md): where the rule and
  the capability live.
- [ADR-CVM-010](ADR-CVM-010-reading-and-sending-text-messages.md): the plugins
  that first carry it.
