# ADR-CVM-003: The app's internal design

## Status

Proposed 2026-11-08. Settled with the owner by voice on 2026-11-08.
Amended 2026-11-11: `earbuds` added, and built first among the plugins, as
The owner decided on #26.
Amended 2026-11-13: the listener's service type is special use,
not data sync, because of Android 15's six-hour limit.
Amended 2026-11-17: what the log of every call holds, where it is kept and for
how long, and that nothing the app keeps leaves the phone in a backup or a
transfer to a new phone. Proposed by the architect session; how long the log
keeps calls is the owner's choice, made by merging this.
Amended 2026-11-17, if the owner agrees: a poll that finds nothing is counted
once a day, not logged line by line.
Amended 2026-11-20 by the architect session: the listener comes back after a
restart or an update, and the app says so when the owner has hidden its
notification. This fills in the special-use service of 2026-11-13 and changes
no direction.
Nothing beyond [the audio-focus prototype](../docs/prototype-audio-focus.md) is
built until the owner accepts this record.

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
  - From Android 15, for an app that targets it, a data-sync foreground service
    may run at most six hours in any 24. After that Android stops it.
  - Whether a foreground service keeps the network through Doze, or the app
    must also be excused from battery optimisation, is measured with the
    listener.
  - From Android 12, an app may start a foreground service from the
    background only in listed cases. Receiving `BOOT_COMPLETED` or
    `MY_PACKAGE_REPLACED` is one of them.
  - From Android 15, an app started by `BOOT_COMPLETED` may not start a
    foreground service of the data-sync, camera, media-playback, phone-call,
    media-projection or microphone type. Special use is not on that list.
  - An app the owner has force-stopped gets no broadcast, `BOOT_COMPLETED`
    included, until the owner opens it again.
  - From Android 13, an app needs `POST_NOTIFICATIONS` to show a
    notification. If the owner refuses, a foreground service still runs, but
    its notification is hidden. It shows only in the list of running apps in
    the quick settings.
- **Sound is shared through audio focus.** An app asks for focus to play. When
  another app asks too, Android tells the first to stop or to *duck*: play on,
  quieter. Whether the Claude app's voice mode lets music duck is not documented.
  It is the riskiest assumption here.
- **The owner wants one app on the phone**, while the companion page stays in
  claude-voice-ui, a browser window onto the hub from any device.

## Options considered

- **One module holding everything.** Not taken: nothing would stop one phone
  function reaching into another, or the core growing a feature.
- **A core module and one module per plugin, in Kotlin, with the dependency rules
  checked in CI.** **Chosen.**
- **Pausing music while Claude talks.** Not taken: the owner asked for music and
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
special-use type for the tailnet listener, described as "a listener for the
owner's own assistant hub". The data-sync type was the first choice. Android 15
stops it after six hours a day, and that would leave the phone deaf to the hub
for the rest of the day. Special use has no time limit, and the app is
installed by its owner, not through a store that would have to approve it. It
adds location, microphone, camera, media playback or phone call only while a
plugin needs it, and drops it after. Its notification is always shown, and says
which of those are on.

The manifest needs these pieces for it:

- the permissions `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`,
  and `POST_NOTIFICATIONS`, asked for at first start, so that the notification
  can be shown;
- on the service, `android:foregroundServiceType="specialUse"`, and the
  property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` holding the
  description above;
- the permission `RECEIVE_BOOT_COMPLETED`, and a receiver for
  `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, as the next part says;
- for each later type, its own `FOREGROUND_SERVICE_<TYPE>` permission, and the
  type added to the service's list. The manifest lists every type the service
  may ever hold, and the service starts with only the ones in use. A plugin
  that adds a type changes the manifest in the same pull request.

**The listener comes back after a restart or an update.** The app has a
receiver for `BOOT_COMPLETED` and for `MY_PACKAGE_REPLACED`, declared in the
manifest with `RECEIVE_BOOT_COMPLETED`. It is not exported, so no other app can
send to it, and it acts on those two actions only. On either, it starts the
service with the special-use type only, and only if the phone is paired with a
hub. Any other type is added later, by a plugin, from the app in the
foreground. So the Android 15 limit above does not stop it. The receiver waits
for `BOOT_COMPLETED`, after the first unlock, and not for the locked boot,
because the hub's key is in storage that opens only then. It does nothing
else, and starts nothing when the app is not paired. After a force stop the app
stays off until the owner opens it, which is Android's rule and the owner's
choice.
Whether the listener truly answers the hub after a restart and after an
update is measured on the phone, as with Doze, before this is relied on.

**The listener comes back after Android kills the app.** The service returns
`START_STICKY`. Android kills the app whenever the owner takes a permission
away, and may kill it when memory runs short. Without this, the hub hears
nothing until the owner next opens the app. With it, Android creates the
service again, with no intent. The service then does what it does at any start.
It goes to the foreground with the special-use type only, and stops at once
when the phone is not paired. A force stop is not undone this way, which stays
Android's rule. On the owner's phone the recreated service went back to the
foreground at once (#152).

**When the owner refuses notifications.** The listener runs anyway, with its
notification hidden. The app's main screen then shows one line: "The listener
is running, but Android hides its notice. Tap to show it." The tap always asks
for `POST_NOTIFICATIONS` first. Android may answer without showing anything,
because the owner has refused it twice. The app then opens its notification
settings (`ACTION_APP_NOTIFICATION_SETTINGS`) instead.

The app does not choose from `shouldShowRequestPermissionRationale` alone. That
flag is false both when Android will no longer ask and when the owner closed
the first request without answering. In the second case asking still works,
and opening the settings would be the wrong page.

So the app watches whether a request was shown. When Android shows it, the
app's screen pauses, or loses the top place when two apps share the screen. A
refusal that comes back with neither counts as Android not asking. No time is
guessed, so a slow phone cannot make the tap ask in vain. As a backstop, a
second refusal in a row from the line opens the settings too. So the line never
becomes a button that does nothing. The choice is a rule in the pure rules
code, which the app only feeds with what it saw.

The line goes once the permission is granted. The app never asks on its own
more than once at first start.

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
- **Empty polls are counted, not logged.** A tool that the hub calls on a timer
  to ask for something new, such as new text messages, may mark itself as a
  poll. A call to it that is answered with nothing new is not a line of its
  own. Instead, the empty polls of a tool are counted, and the count is written
  as one line, with the tool and the outcome `empty`, when the UTC day ends and
  when the app stops, and then starts again from zero. The screen shows the
  running count without writing it. A crash loses at most that count. A
  poll that returns something, is refused, or fails is logged like any call.
  Without this, a poll every 15 seconds would make more than 5 000 lines a
  day, and push every other call out of the log.
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
  from the hub waits for the owner's spoken yes, as ADR-CV-007 sets.

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
- **Android's notification shows the owner what the app is using**, at every
  moment. When the owner has hidden it, the app's own screen says so.
- **The phone answers the hub again after a restart or an update** without
  the owner opening the app, unless the owner force-stopped it.
- **Tests, written first, with fakes for Android's classes:**
  - the receiver starts the service with the special-use type only, on
    `BOOT_COMPLETED` and on `MY_PACKAGE_REPLACED`, and starts nothing when
    the app is not paired or on any other broadcast;
  - the merged manifest holds the receiver with `android:exported="false"`,
    its two actions and no other, `RECEIVE_BOOT_COMPLETED`, and no
    locked-boot action. The system still delivers its own broadcasts to a
    receiver that is not exported, and no other app can reach it;
  - with notifications refused, the line is shown, and a tap asks for the
    permission. A refusal with no pause opens the notification settings, and
    a second refusal in a row opens them too. A first request closed without
    an answer asks again on the next tap, not the settings. The line goes
    once the permission is granted;
  - measured on the phone and written down in `evidence/`: the hub reaches the
    listener after a restart and after an update, with the screen off.
- **Worse:** Gradle modules and a foreground service are more structure than a
  first app needs. They are chosen because the rules they enforce are the point.
- **Worse:** if the Claude app does not let music duck, this app cannot fix that
  alone.
- **Worse:** the page in a web view depends on the hub being reachable. Away from
  the tailnet, the app shows nothing there.

## Related

ADR-CVM-001 (a native app), ADR-CV-007 and ADR-CV-006 in claude-voice,
[`docs/prototype-audio-focus.md`](../docs/prototype-audio-focus.md),
`docs/organisation.md`. The layer rules are amended by
[ADR-CVM-007](ADR-CVM-007-modules-each-install-picks.md).
