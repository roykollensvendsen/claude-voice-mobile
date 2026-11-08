# ADR-CVM-002: How a change is made: owned parts, tests first, merged when green

## Status

Proposed 2026-11-08, following Roy's instructions by voice the same day.

## Context

Several Claude Code sessions build this app, as in Roy's math-quest project. Roy
often follows from his phone, or while driving. claude-voice and claude-voice-ui
already work tests first, one pull request per change, landed by rebase
(ADR-CV-005 in claude-voice).

## Options considered

- **One session for everything.** Not taken: the protocol would drift with
  whatever the app needed that day.
- **Roy reviews every merge.** Not taken: he is often away from a screen.
- **Owned parts, tests first, merged when green, with only real choices going to
  Roy.** **Chosen.**

## Decision

- Each part has one owner session, listed in `OWNERS.md`. Another session
  proposes a change there through an issue or pull request.
- Every feature or fix starts as a failing test, committed before the code that
  turns it green.
- A change reaches `main` through a pull request landed by rebase. It is merged
  when its checks are green and the part's owner agrees.
- What only Roy decides goes to him as an issue labelled `needs-roy`, and in
  `STATUS.md`. The list is in `docs/organisation.md`.

## Consequences

- The history shows, change by change, what was asked and that it was checked.
- Worse: a one-line fix still costs a test, a pull request and a wait for CI.
- Worse: while the repository is private, the forge may refuse to lock `main`.
  The rule is then kept by practice, and `decisions/deferred.md` says so.

## Related

`CONTRIBUTING.md`, `OWNERS.md`, `docs/organisation.md`, ADR-CV-005 in
claude-voice.
