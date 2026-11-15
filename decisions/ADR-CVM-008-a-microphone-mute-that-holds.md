# ADR-CVM-008: A microphone mute that holds

## Status

Proposed 2026-11-15 by the architect session, on the owner's yes to a quick
microphone mute (option a: measure first), relayed by the manager session. The
measurements come first. The design below is built only if they pass, and
settles when the owner merges this record.

## Context

- The owner talks with Claude by voice, through the Claude app on the phone and
  a pair of earbuds. When someone nearby speaks to the owner, the owner must
  answer at once, and **nothing they say may reach Claude**. The Claude
  conversation must not end: after an unmute, Claude hears the owner again in
  the same conversation, with its context kept.
- The Claude app is another app. This app cannot stop its recording, only the
  phone's microphone as a whole. Android has a call for that,
  `AudioManager.setMicrophoneMute`. Its documentation says it is meant for the
  phone's own telephony app. Whether it silences another app's capture, the
  earbuds' microphone over Bluetooth included, is not documented. So it is
  measured, on the owner's phone, before anything is built on it.
- Android 12 and later also have the owner's own switch for microphone access,
  in Quick Settings. No app can turn it on or off; only the person can. It is
  measured as well, as the fallback.
- What is known about the owner's earbuds, from public pages only (OpenSCQ30, a
  GPL-3.0-or-later app for the same make):
  - each earbud's single, double, triple and long press can be remapped (seen);
  - the actions are volume, previous and next, ambient sound, voice assistant,
    and play or pause. There is no mute action (seen);
  - a tap sends no event of its own to the phone, it only does its action
    (partly unverified).

  So the likely path is a tap mapped to play or pause, caught by this app as a
  media button. Whether that still reaches this app while the Claude app holds
  its conversation is unknown. It is measured.

## The measurements, first

A test app, `prototype/mic-mute`, like the audio-focus prototype. It is
installed on the owner's phone only with the owner's yes, and removed
afterwards.

**What is kept, and where.** Each run writes a log with times to a millisecond.
The logs, the numbers, the recordings and the Claude transcript never go into
git. They are kept outside the repository, in
`~/.local/state/claude-voice/measurements/mic-mute/` on the laptop. The test
app keeps its own recordings in its private storage, which removing it
deletes. The recordings of the room are deleted once the results are written.
The repository records only **pass or fail for each measurement**, with the
date and `phone-a`, in `docs/measure-mic-mute.md`.

**First, the Claude app's real path.** During a Claude voice conversation, the
test app reads `AudioManager.getActiveRecordingConfigurations()`. That gives
the audio source the Claude app records with (`MIC`, `VOICE_RECOGNITION` or
`VOICE_COMMUNICATION`) and the device: the built-in microphone, or the earbuds
over Bluetooth SCO or LE Audio. The matrix below is measured for every path
the Claude app is seen to use, and for both mutes: (A) `setMicrophoneMute` from
the test app, and (B) the system switch for microphone access.

Android hides which app another app's recording configuration belongs to, so
the Claude app's entry is found by elimination. During the conversation the
test app records nothing of its own in M1b, and the Claude app's capture must
be the only active one. When more than one capture is active, that reading
fails M1b. It is not guessed at.

| Path the Claude app uses | Mute (A) | Mute (B) |
|---|---|---|
| its source, built-in microphone | M1a, M1b, M1c | M1a, M1b, M1c |
| its source, earbuds over SCO | M1a, M1b, M1c | M1a, M1b, M1c |
| its source, earbuds over LE Audio, if used | M1a, M1b, M1c | M1a, M1b, M1c |

**M1. Does a phone-wide mute stop all sound reaching the Claude app?**

- *M1a, a stand-in.* A laptop speaker plays a steady tone. The test app records
  it with the Claude app's source and device, then mutes. **Pass:** every sample
  recorded while muted is zero.
- *M1b, Android's own word.* Through every muted interval, the recording
  configuration of the Claude app's capture reports `isClientSilenced()` as
  true. It is read every 50 ms. **Pass:** true at every reading, from the mute
  to the unmute.
- *M1c, end to end.* In a real Claude voice conversation, the laptop speaks a
  list of made-up words at logged times. The test app mutes before some of them
  and not others, on a schedule written down beforehand and never said aloud.
  Afterwards the Claude app's transcript of the conversation is read. **Pass:**
  no word spoken while muted appears in it, and every word spoken while unmuted
  does. That second half shows the test could have failed. A word missing from
  the transcript does not prove silence on its own, which is why M1b is there.
  Three runs. "The voice went quiet" is not a pass.

**M2. Can this app see an earbud tap while the Claude app holds the
conversation?** One gesture on one earbud is mapped to play or pause. The
earbuds' old mapping is written down first and put back afterwards. The test
app holds a media session.

- **Pass:** each of 20 taps reaches the test app during a Claude conversation.
- **Also recorded:** whether the tap reaches the Claude app as well, as a pause or
  an end. **That fails the tap,** however well this app sees it.
- **Then repeated:** with a plain Bluetooth headset with a play button, which is
  the default adapter below.

**M3. Do the conversation and its context carry on?** Before muting, the owner
tells Claude a word. The mute lasts 30 seconds, then 5 minutes. After unmuting
the owner asks Claude for the word. **Pass:** the conversation never ended or
restarted, and Claude gives the word.

**M4. How fast?** The laptop plays the steady tone and the stand-in records.
Twenty taps on the earbud, and twenty on the phone's own button, measured from
the moment the tap reaches this app to the first silent sample. **Target:**
300 ms at the 95th percentile. The delay from the earbud to the phone is
measured too, as far as it can be seen.

**M5. Does the mute hold?** During a 10-minute mute in a Claude conversation,
the test app watches Android's mute state, its change broadcast and
`isClientSilenced()`. **Pass:** nothing turns the microphone back on, not even
for a moment. An unmute seen while muted is a leak, even one put right within
100 ms, and fails M5.

**M6. Can the owner hear the tones?** During a Claude conversation, with the
Claude app speaking and silent, the mute and unmute tones are played in the
earbuds twenty times each. **Pass:** the owner hears every one, and can tell
the two apart.

**What the results decide:**

- If (A) passes M1, M3 and M5 on every path in the matrix, the design below
  uses it.
- If (A) fails but (B) passes, this app cannot mute for the owner. The tap then
  gives the warning below, and the button opens the system switch.
- If both fail, **ending the call** is the only mute, and the record says so.
  That is the fallback, never the design.
- If the tap fails M2, the phone's buttons below are the main way, and the
  earbuds' tap waits for a better path.

## Decision, if the measurements pass

**The mute is a capability, `capabilities/micmute`**, as ADR-CVM-007 defines
one. It runs entirely on the phone, and knows nothing of the hub. Its interface
is `mute()`, `unmute()`, `toggle()` and a `state` that is one of `unmuted`,
`muted` or `not holding`.

- **It mutes at once, in one action.** No call to the hub is made on the way.
- **It watches that the mute holds.** It listens for Android's mute changes,
  and checks that every active capture is silenced. Any unmute seen while
  muted is a leak, however short. Each time, it mutes again at once, and warns
  the owner with a tone and the notification: "The microphone was open for a
  moment." After three leaks within 30 seconds it stops fighting. The state
  becomes `not holding`, a warning tone plays, and the notification says
  plainly: "The mute is not holding. End the call."
- **Each toggle has a sound and a vibration,** so the owner knows the state
  without looking. Muting plays a short falling tone. Unmuting plays
  ADR-CV-007's warning sound, and the microphone reopens only after it has
  played.
- **A tap is debounced.** A toggle within 700 ms of the last one is ignored, so
  one tap that arrives twice never mutes and unmutes at once.
- **After a crash or a restart,** it reads Android's real mute state and shows
  that. It never unmutes on starting.

**Headsets come in through adapters**, each a module behind one interface,
`HeadsetAdapter`. It starts and stops listening, and reports a toggle.

- `micmute-mediabutton`, **the default:** a media session that catches play or
  pause from any headset.
- `micmute-soundcore`: it writes one setting on Soundcore earbuds, the chosen
  gesture as play or pause, and nothing else. It keeps that gesture's old
  action, and puts it back when switched off. It credits OpenSCQ30.
  Its code is GPL-3.0-or-later and this app is GPL-3.0, so the licences allow
  borrowing. Any code borrowed keeps its copyright lines.
- The adapter, the make and the gesture are phone settings. No model, name or
  address is written into the code.

**The ways to mute, in order:**

1. **A tap on the earbuds**, the main way: a toggle, where a second tap unmutes.
2. **A button in the notification and a Quick Settings tile.** Each is one touch,
   from the lock screen too, for when no earbuds are in.
3. **Saying "mute the mic".** The voice calls the hub's tool, and the hub calls
   the phone. That is slow: Claude has to hear and handle it, and **whatever is
   said in the meantime reaches Claude.** So it is named as the secondary way,
   and its words say so.

**Unmuting is only on the phone or the earbuds,** never by voice and never by
the hub. A muted voice cannot hear "unmute" anyway, and the hub may never open a
microphone (ADR-CV-007's privacy rules). So the hub's tools, in a plugin
`plugins/micmute` on top of the capability, are `micmute_mute` and
`micmute_state`. There is no unmute tool.

**The plugin's `uses` is empty.** The protocol's `microphone` means a plugin
that can reach the microphone's sound. With the owner's microphone setting at
*none*, a plugin that uses it would have the whole phone refused. This plugin
can only close the microphone, never open or hear it, and `micmute_state` tells
the hub only muted, unmuted or not holding. So it reaches nothing private through the microphone.

It does tell the hub one thing about the owner: a mute usually means the owner
is talking with someone nearby, and `micmute_state` says when that is. Whether
that counts as private, and so needs a value in `uses`, is the voice
architect's judgement, and this record follows it.

**Permissions:** `MODIFY_AUDIO_SETTINGS`, which Android grants without asking,
and the notification permission. The Soundcore adapter adds Bluetooth connect.
The product never asks to record audio. Only the test app does.

**Order:** now, ahead of the data estimate and the earbuds plugin, as the owner
asked: the test app, then the measurements, then the capability with the
default adapter and the phone's buttons, then the Soundcore adapter, then the
hub's tools.

## Consequences

- The owner can answer someone nearby with one tap, and the Claude
  conversation carries on.
- What the mute really does is measured on the owner's phone, with a test that
  could fail, before anyone relies on it.
- Others can use the capability with any headset, without the hub.
- If an Android update changes what `setMicrophoneMute` does, the watch catches
  the mute not holding, and says so, rather than leaving it silently open.

## Related

- [ADR-CVM-007](ADR-CVM-007-modules-each-install-picks.md): capabilities, and
  modules picked per install.
- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): the core, the plugins
  and the foreground service.
- [`docs/design-earbuds.md`](../docs/design-earbuds.md): the earbuds plugin,
  which may share the Soundcore link code with the adapter.
