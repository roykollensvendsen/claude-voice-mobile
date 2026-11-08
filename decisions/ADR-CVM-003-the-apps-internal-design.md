# ADR-CVM-003: The app's internal design

## Status

Proposed 2026-11-08. Settled with Roy Kollen Svendsen by voice on 2026-11-08.
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
## Options considered

- **One module holding everything.** Not taken: nothing would stop one phone
  function reaching into another, or the core growing a feature.
- **A core module and one module per plugin, in Kotlin, with the dependency rules
  checked in CI.** **Chosen.**
## Decision

**A core, and plugins around it, as Gradle modules.**

- `core`: pairing, the tailnet listener, the protocol version, per-plugin
  approval and the log of every call. It holds no phone function.
- `plugin-api`: the one interface a plugin implements: its name, its tools and
  their inputs, as the plugin format in claude-voice specifies.
- `plugins/<name>`: one module each: `status` (with find-phone),
  `notifications`, `location`, `phone`, `playback`, `call`, and later `camera`
  and `sensors`.
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

**Built in this order:**

1. The audio-focus prototype, measured, its findings written down.
2. `core`, `plugin-api` and `status`, with the layer rules in CI, on a frozen
   protocol version.
3. Then each plugin in the order ADR-CV-007 lists, one pull request at a time.

## Consequences

- **Each phone function can be added, removed or left out** without touching the
  others.
- **Android's notification shows Roy what the app is using**, at every moment.
- **Worse:** Gradle modules and a foreground service are more structure than a
  first app needs. They are chosen because the rules they enforce are the point.

## Related

ADR-CVM-001 (a native app), ADR-CV-007 and ADR-CV-006 in claude-voice,
[`docs/prototype-audio-focus.md`](../docs/prototype-audio-focus.md),
`docs/organisation.md`.
