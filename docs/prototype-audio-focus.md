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

Measured on Roy's phone, three rounds so far, all with the test tone.

| Round | Output | Mode | Order | What happened |
|---|---|---|---|---|
| 1 | phone speaker | player ducks itself | Claude first, then the music | Claude's voice mode dropped at once; the music played on at full volume until stopped |
| 2 | phone speaker | takes no focus | Claude first, then the music | the call held and both sides heard clearly, but the music stayed at full volume the whole time |
| 3 | Bluetooth headset | takes no focus | Claude first, then the music | the call held and Claude heard Roy; the music was ducked from its start to the end of the call, also in silences, and both sounded quite good in the headset |

| 4, 5 | Bluetooth headset | player ducks itself, by mistake | Claude first, then the spoken book | Claude's call dropped the moment the book started, both times |

| 5, continued | Bluetooth headset | player ducks itself | the book playing, then Claude started again | the call held, Claude heard Roy, and the book ducked itself the moment Claude asked for focus for a while |
| 6 | Bluetooth headset | takes no focus | the tone first, then Claude | the tone ducked from the moment the phone went into call mode, but Claude did not hear Roy |

Rounds 4 and 5 were meant for the mode that takes no focus. After a reinstall
the app had gone back to the first mode, which asks for focus the ordinary way.
The log shows the phone leaving its call mode within a second of that request:
the same failure as round 1, not a fault of the spoken book. The safe mode is
now the default and is remembered.

The log also shows what the Claude app does. When it takes the sound back, it
asks for focus for a while (a transient request). Through a Bluetooth headset
it plays and listens as a phone call. On the phone speaker it plays as
ordinary media and listens as speech recognition.

So far the order decides it. If the music starts while Claude is already
talking, a player that asks for focus ends Claude's call (rounds 1, 4 and 5),
and a player that asks for none leaves the call alone (rounds 2 and 3). If
the music starts first and Claude second, both kinds of player get out of
Claude's way: the one holding focus is told to pause for a while and ducks
instead (round 5, continued), and the one holding none ducks when the phone
goes into call mode (round 6). For the real app that points at one rule: ask
for focus only when no call or assistant is active, and never pause, only
duck. Whether Claude hears Roy over ducked music is not settled: yes in round
5, no in round 6, one try each.

Round 3 is the case that matters most: Roy's main use is jogging with a
Bluetooth headset, a podcast playing, talking to Claude. It passed with the
test tone. It still has to be run with spoken audio, which is harder for the
echo cancelling, and in the other order.

Rounds 2 and 3 differ in what the Claude app did, as far as the app could see.
Through the headset, Claude's voice mode put the phone in a call mode or played
as a call, and the watching player ducked for the whole conversation. On the
phone speaker, neither happened, so it did not duck. The player now also ducks
while any other app records from the microphone, because a voice assistant
always listens and this app never does. Which signal the Claude app gave in
each round is a guess until the logs are read.

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
