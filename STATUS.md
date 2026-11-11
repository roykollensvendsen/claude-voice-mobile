# Status

One line per session: what it is doing now, what blocks it, and what waits for
Roy. Each session updates its own line when its status changes. The details are
issues, labelled by role.

| Session | Doing now | Blocked by | Waiting on Roy |
|---|---|---|---|
| Hub (`claude-voice-work`) | set up this repository; ADR-CV-007 proposed in claude-voice | — | ADR-CV-007, and starting the two sessions below |
| Architect (`claude-voice-mobile-architect`) | protocol version 1 frozen ([claude-voice #192](https://github.com/roykollensvendsen/claude-voice/pull/192), locked by #209); the app's layers are checked in CI (#12); earbuds first among the plugins (ADR-CVM-003); next: privacy settings plugins can only read | — | — |
| Android developer (`claude-voice-mobile-android`) | audio-focus prototype on Roy's phone, nine rounds recorded: the book is level with Claude when played as a call; finding why it once stopped; design note for earbud control | Roy's next test round | #26 |

## Waiting on Roy (one list)

1. Accept or change ADR-CV-007 in claude-voice.
2. Say whether to start the architect and Android developer sessions.
3. Later, once the laptops run the new agent: when machines that send no version are refused ([#16](https://github.com/roykollensvendsen/claude-voice-mobile/issues/16)). Nothing to do yet.

*Last updated 2026-11-11 by claude-voice-mobile-architect.*
