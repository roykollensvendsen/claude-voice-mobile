# ADR-CVM-003: The app's internal design

## Status

Proposed 2026-11-08. Settled with Roy Kollen Svendsen by voice on 2026-11-08.
Amended 2026-11-11: `earbuds` added, and built first among the plugins, as
Roy decided on #26.
Amended 2026-11-17: what the log of every call holds, where it is kept and for
how long, and that nothing the app keeps leaves the phone in a backup or a
transfer to a new phone. Proposed by the architect session; how long the log
keeps calls is the owner's choice, made by merging this.
Nothing beyond [the audio-focus prototype](../docs/prototype-audio-focus.md) is
built until he accepts this record.

What the hub depends on, the agent protocol, the plugin format and the privacy
rules, is [ADR-CV-007 in claude-voice](https://github.com/roykollensvendsen/claude-voice/blob/main/decisions/ADR-CV-007-a-phone-agent-and-voice-calls.md).
This record is how the app keeps them inside. It builds on ADR-CVM-001, which
chose a native Android app.

## Context

- **The app is an agent for the hub**, offering the phone's functions as plugins.
  It runs no Claude Code sessions.
- **Android limits background work.** These are vendor facts, to be measured:
  - Doze stops background apps while the screen is off.
  - From Android 11, the microphone works in the background only from a
    foreground service of the microphone type.
  - From Android 14, each foreground service declares what it is for: location,
    microphone, camera, media playback or phone call.
- **Sound is shared through audio focus.** An app asks for focus to play. When
  another app asks too, Android tells the first to stop or to *duck*: play on,
  quieter. Whether the Claude app's voice mode lets music duck is not documented.
  It is the riskiest assumption here.
- **Roy wants one app on the phone**, while the companion page stays in
  claude-voice-ui, a browser window onto the hub from any device.

## Options considered

- **One module holding everything.** Not taken: nothing would stop one phone
  function reaching into another, or the core growing a feature.
- **A core module and one module per plugin, in Kotlin, with the dependency rules
  checked in CI.** **Chosen.**
- **Pausing music while Claude talks.** Not taken: Roy asked for music and
  Claude's voice to run together.
- **Copying the companion page into the app.** Not taken: two copies of the page
  would drift. The app shows the hub's page in a web view instead.

## Decision

**A core, and plugins around it, as Gradle modules.**

- `core`: pairing, the tailnet listener, the protocol version, per-plugin
  approval and the log of every call. It holds no phone function.
- `plugin-api`: the one interface a plugin implements: its name, its tools and
  their inputs, as the plugin format in claude-voice specifies.
- `plugins/<name>`: one module each: `status` (with find-phone), `earbuds`
  (the earbuds' sound mode, equalizer and battery), `notifications`,
  `location`, `phone`, `playback`, `call`, and later `camera` and `sensors`.
  A plugin that needs a link of its own, such as Bluetooth to the earbuds,
  owns it and opens it only for a call; the core hands out no phone function.
- `app`: the screens, the settings and the service.
- **The layer rules, checked in CI:** the core never depends on a plugin; a plugin
  depends only on `plugin-api`; no plugin depends on another.

**One foreground service, holding only the types in use.** It starts with the
data-sync type for the tailnet listener. It adds location, microphone, camera,
media playback or phone call only while a plugin needs it, and drops it after.
Its notification is always shown, and says which of those are on.

**Settings that only the phone changes.** The microphone setting, location
logging, the list of people who may be called straight away and the camera are
changed in the app's own screens. No tool the app offers to the hub writes them.
A test proves each.

**The log of every call stays on the phone, and says little.** Protocol version 1
asks every agent to log each call, and to keep the log on the machine.

- **Where.** One file in the app's private storage, one JSON line per call. The
  core writes it through a small interface, and the app gives it the file, so the
  core stays plain Kotlin. Each line is encoded as JSON, never pieced together,
  so a session name cannot break a line. A line is appended on its own; pruning
  writes a new file and renames it, so a crash loses at most the newest line.
- **What a line holds.** The time, in UTC; the tool; the hub's node; the caller
  from the call's `_meta`, `voice` or a session's name; and what came of it:
  `answered`, `refused`, `late` (past the time limit), `broke` (the plugin
  failed, with the error's class only, never its message), `not_run` (it
  waited too long for a thread and never reached the plugin, so its call id is
  freed for a retry), `duplicate` or `busy`. **Never a call's inputs or its answer, for any tool.** Version 1 allows
  them for a tool that uses nothing private, but nothing needs them, and a log
  without them cannot become a record of what the owner asked or heard.
- **What it leaves out.** Requests the gate refused, such as a wrong key, never
  reached a plugin. They are counted elsewhere, not logged here.
- **How long.** 30 days, and at most 5 000 lines, whichever is less; the oldest
  go first, when a line is written, when the app starts and when the log is
  read, so a quiet phone keeps nothing longer either. This is the owner's
  choice. The numbers are proposed here, and changing them changes this record.
- **Who reads it.** Only the owner, on the phone. No tool the app offers reads
  it, and a test proves that, as for the settings above. The app's screen can
  show it and delete all of it at once.

**Nothing the app keeps leaves the phone.** The pairing, the pinned hub, the
settings and the log are excluded from Android's backup and from a transfer to a
new phone. The manifest turns backup off, and its data extraction rules exclude
everything for both, since from Android 12 turning backup off alone no longer
stops a transfer between phones. A test reads the merged manifest. A new phone is
paired again, as the record on how the phone knows its hub, ADR-CVM-009,
assumes.

**Playback runs beside Claude's voice, never instead of it.**

- `playback` plays podcasts and music from the hub's Audiobookshelf and Navidrome
  plugins, through Media3 as a media-playback service.
- When another app takes audio focus to talk or listen, it ducks and comes back.
  It never pauses for Claude.
- **This is tested first**, before anything else is built. The prototype
  plan says how: with and without a headset, wired and Bluetooth, with the
  player ducking itself and with Android doing it. If the Claude app does not let
  music duck, this record is reopened.

**Phone calls go through Android's own dialler.**

- `phone` places and ends ordinary calls through Android's Telecom service, with
  the permission to place calls. Android's own call screen shows the call.
- A number on the phone's own list is called straight away. Any other number
  from the hub waits for Roy's spoken yes, as ADR-CV-007 sets.

**The companion page shows inside the app.** A screen of the app opens the hub's
page in a web view, signed in like the browser. The page's code stays in
claude-voice-ui. The app adds only what a browser cannot: the service and the
plugins.

**Built in this order:**

1. The audio-focus prototype, measured, its findings written down.
2. `core`, `plugin-api` and `status`, with the layer rules in CI, on a frozen
   protocol version.
3. `earbuds`: small, used every day, and needing nothing from the playback
   work. Its design is [`docs/design-earbuds.md`](../docs/design-earbuds.md).
4. Then each plugin in the order ADR-CV-007 lists, one pull request at a time.
5. Location triggers, decided by the owner on 2026-11-15 with only a spoken
   yes: after the `location` plugin and protocol version 2, as
   [`docs/design-location-triggers.md`](../docs/design-location-triggers.md)
   says.

## Consequences

- **Each phone function can be added, removed or left out** without touching the
  others.
- **Android's notification shows Roy what the app is using**, at every moment.
- **Worse:** Gradle modules and a foreground service are more structure than a
  first app needs. They are chosen because the rules they enforce are the point.
- **Worse:** if the Claude app does not let music duck, this app cannot fix that
  alone.
- **Worse:** the page in a web view depends on the hub being reachable. Away from
  the tailnet, the app shows nothing there.

## Related

ADR-CVM-001 (a native app), ADR-CV-007 and ADR-CV-006 in claude-voice,
[`docs/prototype-audio-focus.md`](../docs/prototype-audio-focus.md),
`docs/organisation.md`.
