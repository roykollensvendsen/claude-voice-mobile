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

Each run is done in both orders: start the prototype playing, then use the
Claude app's voice mode for two minutes; and start voice mode first, then the
prototype. Ask a question, let it answer at length, interrupt it once.

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

Three variants of the player are run each time: one handling focus itself
(lower the volume on a duck), one leaving the duck to Android, and one that
never asks for focus and lowers itself while Claude's voice plays.

## What counts as passing

Every run ducks and comes back, without a pause, and the conversation with Claude
is as clear as without music. A run that pauses is a fail, even if the music
resumes.

## Findings

Measured on Roy's phone, one round so far.

| Run | Mode | Order | What happened |
|---|---|---|---|
| 1, phone speaker | player ducks itself | Claude's voice mode first, then the music | Claude's voice mode dropped at once; the music played on at full volume until stopped |

What this shows: a player that asks Android for the speaker the ordinary way
(permanent media focus) cuts off Claude's voice mode if it starts while Claude
is talking. The likely cause is that Android passes that request on to the
Claude app as a permanent loss of focus, and the Claude app ends its
conversation. This is a guess until the logged focus changes are read.

That matters for the real app, because "play my podcast" will often be said to
Claude while its voice mode is on. A third mode was added for it: the player
never asks for focus, so it takes nothing from Claude, and lowers itself while
an assistant's voice or a call is playing. The test now runs both orders:
music first, then Claude; and Claude first, then the music.

## If it fails

The findings go back to ADR-CV-007 with the logged focus changes. The options
then include playing through the hub's own speaker instead, or asking Anthropic
how the Claude app requests audio focus. Both are for Roy to choose.
