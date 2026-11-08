# ADR-CVM-001: A native Android app, speaking the protocol claude-voice specifies

## Status

Proposed 2026-11-08, with ADR-CV-007 in claude-voice. It is accepted when that
record is.

## Context

Roy wants his phone to join his claude-voice hub the way a second laptop does,
offering the phone's own functions to the voice. ADR-CV-007 in claude-voice
weighs how, and why this repository exists. The facts and options are there and
are not repeated here.

## Options considered

- **The phone as a client only, through the companion web page.** Not taken: a
  page cannot run with the screen off, place calls or read location in the
  background.
- **Termux running the Python agent.** Not taken: it cannot check callers the way
  the agent does, the Claude Agent SDK has no Android build, and it reaches the
  phone's audio and calls only second-hand.
- **A Kotlin app with a foreground service, in its own private repository.**
  **Chosen.**

## Decision

This repository holds a Kotlin Android app that is a claude-voice agent. It is a
small core (pairing, the tailnet, per-plugin approval, logging) with each phone
function as a plugin. It runs no Claude Code sessions. It speaks the agent
protocol claude-voice specifies. A protocol change starts there, and the app
follows a frozen version, never a draft.

## Consequences

- The app uses Android's own permissions, indicators and foreground
  notification.
- Worse: a second codebase in a second language, with its own build, signing and
  release, all before anything is published.
- Worse: the hub and the app can drift. Only the frozen protocol version and the
  tests against it prevent that.

## Related

ADR-CV-006 and ADR-CV-007 in claude-voice; `docs/protocol.md` once drafted;
[`docs/prototype-audio-focus.md`](../docs/prototype-audio-focus.md).
