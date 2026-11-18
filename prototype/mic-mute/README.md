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
- **The tones (M6):** a random one of the two, on the call or the media
  stream. The tester answers on the screen which they heard.

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
app's transcript. M1c passes only when no muted word reached the transcript
and every unmuted one did.

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
