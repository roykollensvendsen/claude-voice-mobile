# Owners

Who owns which part of the repository. How ownership works, and how to propose a
change to someone else's part, is in [`docs/organisation.md`](docs/organisation.md).

| Path | Owner |
|---|---|
| `docs/protocol.md`, `schemas/`, and the layer rules (which module may depend on which) | architect session (`claude-voice-mobile-architect`) |
| `app/` and the Android build, once they exist | Android developer session (`claude-voice-mobile-android`) |
| `.claude/skills/<role skill>/`: each role's own skill | the role it is for; the owner develops it |
| `.claude/skills/write-commit/` | Roy |
| the hub side: everything in [claude-voice](https://github.com/roykollensvendsen/claude-voice) the phone talks to | `claude-voice-work` (the claude-voice session) |
| `STATUS.md`, the board, labels and issues | every session keeps its own line and issues; `claude-voice-work` keeps the whole tidy |
| everything else (`README.md`, `CONTRIBUTING.md`, `AGENTS.md`, `CLAUDE.md`, `decisions/`, `OWNERS.md`, the rest of `docs/`) | Roy |
