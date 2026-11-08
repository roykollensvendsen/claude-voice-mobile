# For an assistant working here

This repository will hold the claude-voice agent for Android: a Kotlin app
that joins a claude-voice hub the way a second laptop does, and offers the
phone's own functions as plugins. It is private for now.

## What only Roy decides

- **Building beyond the prototype plan.** Nothing past
  [the audio-focus prototype](docs/prototype-audio-focus.md) is built until Roy
  has accepted ADR-CV-007 in claude-voice and said yes here.
- **Installing on a phone.** Putting a build on Roy's phone, or anyone's, needs
  his yes each time.
- **Making the repository public**, and choosing who else can read it.
- **Changing the protocol.** The agent protocol is specified in claude-voice.
  Propose a change there; never let the app drift from it here.

## What never to do

- **Never commit a key**, the hub's pairing key, a signing key or a keystore. The
  app keeps its pairing key in Android's keystore.
- **Never open the microphone before the warning sound has played**, under any
  setting. A change that could let it is refused, whatever it is for.
- **Never let the hub or the voice change** the microphone setting, the
  location-logging setting, the list of people who can be called directly, or
  the camera. They are changed on the phone only.
- **Never pause music or podcasts for Claude's voice.** They duck, and come back.

## Before you push

The gates are in [CONTRIBUTING.md](CONTRIBUTING.md). Run them after your last
edit.
