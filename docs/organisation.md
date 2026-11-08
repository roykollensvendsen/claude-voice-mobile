# How the work is organised

The app is built by a few Claude Code sessions, each owning one part. Roy is
asked only about real choices.

## The sessions

| Session | Owns | Its skill |
|---|---|---|
| Architect (`claude-voice-mobile-architect`) | the hub-to-phone protocol, its schemas, and the layer rules | `mobile-architecture`: how the protocol is versioned, frozen and changed |
| Android developer (`claude-voice-mobile-android`) | the app: its core, its plugins, its build and tests | `mobile-android`: building, testing and installing the app |
| Hub (`claude-voice-work`, in claude-voice) | the bridge side the phone talks to | lives in claude-voice |

Each session writes and improves its own skill, with the skill-creator skill,
as it learns. The architect and Android sessions are started only when Roy says
so.

## The rules the architect owns

- **The protocol has a version**, written at the top of `docs/protocol.md` and in
  every schema. Nothing is built on a version until it is frozen. A frozen
  version is never edited; a change is a new version.
- **The layers**, enforced in CI once the first module exists:
  - the core never depends on a plugin;
  - no plugin depends on another plugin;
  - a plugin depends only on the core's plugin interface.

## Who decides what

Decided by the sessions themselves, and told to Roy in the next summary:

- commits and pushes;
- merging to `main` when the checks are green and the owner of the part agrees;
- who does what, and everyday disagreements between sessions.

Only Roy decides, each as an issue labelled `needs-roy`:

- building past the audio-focus prototype, until ADR-CV-007 is accepted;
- installing anything on a phone;
- making the repository public, licences, money;
- freezing a protocol version;
- starting a new session or role, and changing who owns what.

When a call is cheap to undo, the session makes it. When it is not, it goes to
Roy.

## Where status lives

1. **Issues and the board.** Labels `role:architect`, `role:android`,
   `role:hub`, `needs-roy`, `blocked`.
2. **[`STATUS.md`](../STATUS.md)**: one line per session, then one list of what
   waits for Roy. Each session keeps its own line true.
3. **What Roy hears**: one short summary in plain Norwegian, through
   `claude-voice-work`.
