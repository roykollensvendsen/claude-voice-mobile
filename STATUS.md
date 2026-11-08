# Status

One line per session: what it is doing now, what blocks it, and what waits for
Roy. Each session updates its own line when its status changes. The details are
issues, labelled by role.

| Session | Doing now | Blocked by | Waiting on Roy |
|---|---|---|---|
| Hub (`claude-voice-work`) | set up this repository; ADR-CV-007 proposed in claude-voice | — | ADR-CV-007, and starting the two sessions below |
| Architect (`claude-voice-mobile-architect`) | protocol version 1 reviewed by the hub session ([claude-voice #124](https://github.com/roykollensvendsen/claude-voice/pull/124)); the app's layers are checked in CI (#12) | Roy's yes to freeze it | #15 |
| Android developer (`claude-voice-mobile-android`) | audio-focus prototype built and tested on an emulator | Roy's yes to install it | #10 |

## Waiting on Roy (one list)

1. Accept or change ADR-CV-007 in claude-voice.
2. Say whether to start the architect and Android developer sessions.
3. Say yes to installing the audio-focus prototype on his phone ([#10](https://github.com/roykollensvendsen/claude-voice-mobile/issues/10)).
4. Say yes to freezing protocol version 1, or what to change ([#15](https://github.com/roykollensvendsen/claude-voice-mobile/issues/15)).
5. Later, once the laptops run the new agent: when machines that send no version are refused ([#16](https://github.com/roykollensvendsen/claude-voice-mobile/issues/16)). Nothing to do yet.

*Last updated 2026-11-08 by claude-voice-mobile-architect.*
