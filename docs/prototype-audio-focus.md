# The first prototype: Claude's voice and music together

*Built in [`prototype/audio-focus/`](../prototype/audio-focus/README.md). Not yet measured on a phone.*

## The question

Can a music or podcast player keep playing, quieter, while the Claude app's voice
mode listens and speaks, and come back to full volume after? It must neither
pause nor be cut off, and Claude must hear Roy and be heard.

ADR-CV-007 in claude-voice makes this the hard requirement for the phone app. If
the answer is no, that record is reopened before anything else is built.

## What Android offers

Android shares the speaker through *audio focus*. A player asks for focus to
play. When another app asks for focus too, Android tells the player whether to
stop, or to *duck*: play on, quieter, until focus comes back. From Android 8, the
system can duck a player by itself, if the player allows it. These are vendor
facts. The prototype measures them with the Claude app, whose behaviour is not
documented.

## The prototype

A one-screen Kotlin app with one job: play a long audio file through
`ExoPlayer` (Media3), as a media-playback foreground service, holding media audio
focus. On each change of focus it logs the time, the focus change, the volume it
set and whether it paused.

No hub, no pairing, no plugins. It is thrown away once it has answered the
question.

## The test

Each run: start the prototype playing, then use the Claude app's voice mode for
two minutes. Ask a question, let it answer at length, interrupt it once.

| Run | Output | Microphone |
|---|---|---|
| 1 | phone speaker | phone |
| 2 | wired headset | headset |
| 3 | Bluetooth headset | headset |
| 4 | Bluetooth headset | phone |

For each run, write down:

- whether the music ducked, paused, stopped, or played on at full volume;
- whether it came back afterwards, and how fast;
- whether Claude heard Roy clearly, and was heard clearly;
- the focus changes the prototype logged.

Two variants of the player are run each time: one handling focus itself (lower
the volume on a duck), and one leaving the duck to Android.

## What counts as passing

Every run ducks and comes back, without a pause, and the conversation with Claude
is as clear as without music. A run that pauses is a fail, even if the music
resumes.

## Findings

Not yet measured. The emulator only shows that the app plays, holds media focus
and logs; it has no Claude app, so it answers nothing here.

## If it fails

The findings go back to ADR-CV-007 with the logged focus changes. The options
then include playing through the hub's own speaker instead, or asking Anthropic
how the Claude app requests audio focus. Both are for Roy to choose.
