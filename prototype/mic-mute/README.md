# Mic-mute test app

A throwaway app for the measurements in
[ADR-CVM-008](../../decisions/ADR-CVM-008-a-microphone-mute-that-holds.md):
does Android's phone-wide microphone mute stop every sound reaching the
Claude app's voice conversation, does it hold, how fast is it, and can an
earbud tap reach this app while Claude holds the conversation? It is
installed on the owner's phone only with the owner's yes, and removed once
the pass or fail of each measurement is in
[`docs/measure-mic-mute.md`](../../docs/measure-mic-mute.md).

## What it does

A foreground service holds everything while the Claude app is in front:

- **The mute (A):** `AudioManager.setMicrophoneMute`, from the screen, the
  notification's button, or an earbud tap. A toggle within 700 ms of the last
  one is ignored. Muting plays a falling tone and unmuting a rising one.
- **The watch, every 50 ms:** Android's mute state and every active capture
  (source, device, `isClientSilenced`). Any unmute seen while the app holds
  the mute is logged as a `LEAK` (M5). While muted, with the stand-in off,
  each reading is judged for M1b: exactly one capture, silenced. It logs when
  the verdict changes, and a summary at each unmute.
- **The stand-in (M1a, M4):** records with the source and device picked on
  the screen, the ones the `captures` line shows the Claude app using. It
  logs how much it heard while open, so a pass shows the test could have
  failed. While muted, it notes when silence began and the delay from the tap
  (`m4-delay`, with the 95th percentile so far). From then on any sound fails
  M1a. It keeps no sound, only counts.
- **The media session (M2):** a session kept the media-button target by a
  silent track, so a play or pause from an earbud reaches it as a tap.
- **The word schedule (M1c):** from the next whole minute, twelve 10-second
  slots, a third to two thirds of them muted, chosen at random and written to
  the log. It is never shown on the screen or said aloud.
- **The owner's switch (B):** no app can read it, so the tester marks it by
  hand, and **the marks must always sit inside the real mute**. To mute,
  turn microphone access off in Quick Settings, then tap "B on". To unmute,
  tap "B off" first, then turn access back on. In the other order the
  stand-in hears the tone while marked muted, and the run fails for the
  wrong reason. From the mark, M1a and M1b judge as they do for the app's
  own mute (A). For M1c under B, the owner flips the switch at their own
  times, not shown to anyone, each time at the start of a slot, between
  words. Each change is marked in the order above, and the laptop's judge
  reads the mutes from those marks. M5 watches mute (A) only.
- **One run per log for M1c under B:** the judge checks the last word
  schedule in the log, so tap "Save the log to Downloads" and then "Clear
  the log" between an (A) run and a (B) run.
- **The silent track:** the media session can run with or without it. A
  pass with it is a pass for that trick only, so M2 is run once without it
  too, which shows whether the product would have to play silence all day.
  The log says which.
- **The tones (M6):** a random one of the two, on the call or the media
  stream. The tester answers on the screen which they heard.

**Stopping never leaves the phone muted.** The mute is phone-wide and
outlives the app. When the service stops or the app is swiped away, a mute
this app set is lifted, and the log says `mute-restored`. A mute someone else
set is left alone. If the microphone still seems muted afterwards, open the
test app, start the service and tap "Mute / unmute" until the notification
says "Microphone open", or restart the phone.

`m4-delay` errs long by the recording's own input delay: it is measured to
when the silent sample reached the app, not to when the microphone stopped.

Every event goes to logcat (tag `MicMuteProto`), to a file in the app's
private storage, and to the screen, with times to a millisecond. "Save the
log to Downloads" copies the file out, so the owner can send it to the
laptop. On the laptop it goes in
`~/.local/state/claude-voice/measurements/mic-mute/`, never into git.

## The laptop's side

`laptop.py` plays the steady tone for M1a and M4. For M1c, it waits for the
same whole minute as the phone and says one made-up word in the middle of
each slot. It writes the words and their times to the folder above.
Afterwards it judges M1c from the phone's log, that file and the Claude
app's transcript, by each word's time and the mutes the phone logged, by
either way. M1c passes only when no muted word reached the transcript and
every unmuted one did. A word said across a mute or an unmute is judged
neither way, and makes the run unclear. The judge also lists every other word in
the transcript. The owner reads that list before a pass is written down,
since a muted word the speech engine spelt differently would not match.

The judge refuses a run whose phone schedule began in a different minute
from the laptop's words. The phone's and the laptop's clocks must agree to
within a second; both set from the network do.

<!-- not run: plays sound on the laptop's speaker -->
```
uv run python prototype/mic-mute/laptop.py tone --seconds 120
uv run python prototype/mic-mute/laptop.py words --seed 1
uv run python prototype/mic-mute/laptop.py judge <phone log> <words file> <transcript>
```

## Build and test

Needs JDK 17 and the Android SDK; `local.properties` points at the SDK.

<!-- not run: needs the Android SDK and downloads Gradle and the libraries -->
```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest assembleDebug lintDebug
```

The app is `app/build/outputs/apk/debug/app-debug.apk`, signed with the debug
key. It needs Android 12 or later.

## Install and remove

Installing on the owner's phone needs the owner's yes, each time. Use the
SDK's own `adb`, since the one on the system path may be a different build,
or send the APK with Taildrop.

<!-- not run: installs on a phone -->
```
~/Android/Sdk/platform-tools/adb -d install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb -d uninstall org.claudevoice.prototype.micmute
```

It asks for the microphone, which only this test app does (ADR-CVM-008), and
for notifications. Changing the mute needs `MODIFY_AUDIO_SETTINGS`, which
Android grants without asking.
