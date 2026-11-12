# Status

One line per session: what it is doing now, what blocks it, and what waits for
Roy. Each session updates its own line when its status changes. The details are
issues, labelled by role.

| Session | Doing now | Blocked by | Waiting on Roy |
|---|---|---|---|
| Hub (`claude-voice-work`) | set up this repository; ADR-CV-007 proposed in claude-voice | — | ADR-CV-007, and starting the two sessions below |
| Architect (`claude-voice-mobile-architect`) | protocol version 1 frozen ([claude-voice #192](https://github.com/roykollensvendsen/claude-voice/pull/192), locked by #209); the app's layers are checked in CI (#12); plugins read the privacy settings and never change them (#35); earbuds first among the plugins, after the core (#26); proposals for a microphone mute, location triggers and a plain message when Tailscale is off | Roy's answers | 3, 4 and 5 below |
| Android developer (`claude-voice-mobile-android`) | audio-focus prototype on Roy's phone, nine rounds recorded: the book is level with Claude when played as a call; finding why it once stopped; design note for earbud control | Roy's next test round | #26 |

## Waiting on Roy (one list)

1. Accept or change ADR-CV-007 in claude-voice.
2. Say whether to start the architect and Android developer sessions.
3. Yes or no to a quick microphone mute during a voice call, as proposed:
   - **Scope:** a large button in the app and one in its notification, both showing muted or not at a glance. Muting comes from anywhere, including the voice. Unmuting happens only on the phone, because the hub may never open a microphone. While muted, the voice hears nothing to unmute anyway.
   - **What "truly stops" can mean:** in a call the app owns itself (the later `call` plugin), muting stops sending sound, and a test proves it. For the Claude app's voice mode, which is another app, the only way is Android's mute for the whole phone. Whether that really silences the Claude app is not documented, so it is measured first, like the audio-focus test. It needs your yes to install the test on your phone.
   - **The earbuds' own buttons:** later, only if they can be heard without taking over music control. That is also measured, with the earbuds work.
   - **Order:** the measurement first. Then the button and the notification together with the settings screen, after the earbuds. Saying yes changes ADR-CVM-003, which the architect writes.
4. Yes or no to location triggers ([design](docs/design-location-triggers.md)): near a saved place, the bridge asks, for example about the garage door, and acts only on your yes. Places stay on the phone, and only "arriving at place X" reaches the hub. It needs protocol version 2. One question for you: may a tap on Yes in the phone's notification count, when the voice is not running? The hub session and the architect recommend not yet. Until version 2 has the rule that a tap counts only on an unlocked phone, and never for a door or a lock, the question is skipped when the voice is off. Order: after the earbuds, the settings screen and the mute.
5. Yes or no to a plain message when the phone loses the tailnet. The phone answers the hub only on its tailnet address. When that address is gone, the app says what is wrong and what to do, in its always-shown notification and on its screen:
   - **No VPN on the phone:** "Tailscale looks like it's off on this phone. Start it, and the hub can reach the phone again." A button opens the Tailscale app, or its download page if it isn't installed.
   - **A VPN without a tailnet address:** "Another VPN seems to be on. Tailscale can't run beside it. Turn that VPN off, and start Tailscale." It is a guess, so it names no VPN.
   - **On the tailnet, but the hub can't be reached:** "The phone is on the tailnet, but the hub isn't answering. Is the laptop on and awake?"
   - The test is the phone's own tailnet address, looked for only on the VPN network (IPv4 100.64.0.0/10, or IPv6 fd7a:115c:a1e0::/48). Mobile networks also hand out 100.64 addresses, so an address on any other network says nothing. A VPN alone is not enough either, because another VPN would look the same.
   - "The hub isn't answering" comes from the phone's own traffic with the hub, or from a slow check. The phone never asks often enough to keep the radio awake. It needs no new permission and no protocol change.
   - **Order:** part of the core's tailnet listener, before the earbuds. Without the tailnet, nothing else on the phone works.
6. Later, once the laptops run the new agent: when machines that send no version are refused ([#16](https://github.com/roykollensvendsen/claude-voice-mobile/issues/16)). Nothing to do yet.

*Last updated 2026-11-11 by claude-voice-mobile-architect.*
