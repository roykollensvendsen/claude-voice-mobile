# Status

One line per session: what it is doing now, what blocks it, and what waits for
Roy. Each session updates its own line when its status changes. The details are
issues, labelled by role.

| Session | Doing now | Blocked by | Waiting on Roy |
|---|---|---|---|
| Hub (`claude-voice-work`) | set up this repository; ADR-CV-007 proposed in claude-voice | — | ADR-CV-007, and starting the two sessions below |
| Architect (`claude-voice-mobile-architect`) | protocol version 1 frozen ([claude-voice #192](https://github.com/roykollensvendsen/claude-voice/pull/192), locked by #209); the app's layers are checked in CI (#12); plugins read the privacy settings and never change them (#35); earbuds first among the plugins, after the core (#26); a plain message when Tailscale is off, decided, built with the core's tailnet listener ([design](docs/design-tailscale-off.md)); proposals for a microphone mute and location triggers; how the phone checks the hub's identity | Roy's answers | 3, 4 and 5 below |
| Android developer (`claude-voice-mobile-android`) | audio-focus answer written: music ducks under Claude, the design stands; next the core's tailnet listener with the Tailscale-off message, then the earbuds plugin | — | — |

## Waiting on Roy (one list)

1. Accept or change ADR-CV-007 in claude-voice.
2. Say whether to start the architect and Android developer sessions.
3. Yes or no to a quick microphone mute during a voice call, as proposed:
   - **Scope:** a large button in the app and one in its notification, both showing muted or not at a glance. Muting comes from anywhere, including the voice. Unmuting happens only on the phone, because the hub may never open a microphone. While muted, the voice hears nothing to unmute anyway.
   - **What "truly stops" can mean:** in a call the app owns itself (the later `call` plugin), muting stops sending sound, and a test proves it. For the Claude app's voice mode, which is another app, the only way is Android's mute for the whole phone. Whether that really silences the Claude app is not documented, so it is measured first, like the audio-focus test. It needs your yes to install the test on your phone.
   - **The earbuds' own buttons:** later, only if they can be heard without taking over music control. That is also measured, with the earbuds work.
   - **Order:** the measurement first. Then the button and the notification together with the settings screen, after the earbuds. Saying yes changes ADR-CVM-003, which the architect writes.
4. Yes or no to location triggers ([design](docs/design-location-triggers.md)): near a saved place, the bridge asks, for example about the garage door, and acts only on your yes. Places stay on the phone, and only "arriving at place X" reaches the hub. It needs protocol version 2. One question for you: may a tap on Yes in the phone's notification count, when the voice is not running? The hub session and the architect recommend not yet. Until version 2 has the rule that a tap counts only on an unlocked phone, and never for a door or a lock, the question is skipped when the voice is off. Order: after the earbuds, the settings screen and the mute.
5. How the phone checks that a call really comes from the hub. On the laptops, the agent asks Tailscale which machine is calling. On the phone it can't: Android's Tailscale app doesn't answer other apps. Recommended: at pairing, the phone remembers the hub's tailnet address, and every call must come from that address **and** carry the hub's key. Tailscale guarantees that only the hub can send from its address. The alternative is to build Tailscale into the app itself, which is a much larger build. The hub session puts this to you.
6. Later, once the laptops run the new agent: when machines that send no version are refused ([#16](https://github.com/roykollensvendsen/claude-voice-mobile/issues/16)). Nothing to do yet.

*Last updated 2026-11-13 by claude-voice-mobile-architect.*
