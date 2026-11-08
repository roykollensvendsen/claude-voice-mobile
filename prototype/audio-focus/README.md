# Audio-focus prototype

A throwaway app that answers one question from
[the prototype plan](../../docs/prototype-audio-focus.md): does music keep
playing, quieter, while the Claude app's voice mode talks and listens? It is
deleted once the findings are written into that plan.

## What it does

It plays a test tone (made on the phone, so nothing copyrighted ships), or a
podcast link typed in, through Media3 as a media-playback service. It holds
media audio focus itself and never pauses. Every event goes to logcat (tag
`AudioFocusProto`), to a file, and to the screen:

- each focus change Android delivers, and the volume the player then set;
- which kinds of sound other apps are playing (media, assistant, voice call…);
- which microphone sources are recording, and on which device;
- the phone's audio mode (normal, in a call, in communication);
- headsets connecting and disconnecting;
- what the tester heard, from the buttons on the screen.

Three modes, chosen on the screen:

| Mode | The player | On a "duck" request | On a "pause" request |
|---|---|---|---|
| Ducks itself | asks Android not to duck it | lowers itself to the chosen level | lowers itself to the chosen level |
| Android ducks | lets Android duck it | does nothing; Android may duck | does nothing; plays on |
| Takes no focus | never asks for focus, so takes nothing from Claude | lowers itself to the chosen level while an assistant's voice or a call plays, or the phone is in a call mode | — |

The level while Claude talks is a slider from 10 % to 400 %, starting at
40 %. Above 100 % the app boosts its own sound with Android's loudness
enhancer. The book can also play as media, a call, accessibility or an
assistant, each on its own volume; "Vis volum" opens the volume panel for the
chosen kind, which is otherwise out of reach during a call.

## Build and test

Needs JDK 17 and the Android SDK; `local.properties` points at the SDK.

<!-- not run: needs the Android SDK and downloads Gradle and the libraries -->
```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest assembleDebug
```

The app is `app/build/outputs/apk/debug/app-debug.apk`, signed with the debug
key.

## Install and fetch the log

Installing on a phone needs its owner's yes, each time. Use the SDK's own
`adb`; the one on the system path may be a different build.

<!-- not run: installs on a phone -->
```
~/Android/Sdk/platform-tools/adb -d install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb -d pull /sdcard/Android/data/org.claudevoice.prototype.audiofocus/files/focus-log.txt
~/Android/Sdk/platform-tools/adb -d uninstall org.claudevoice.prototype.audiofocus
```

Or tap *Del loggen* on the phone to share the log as text.
