# ADR-CVM-004: Measuring the app's code health

## Status

Proposed 2026-11-15 by the Android developer session, on Roy's yes, relayed by the
manager session, to put this repository on the bridge's code-health page.

## Context

- The bridge's code-health page (claude-voice, `src/claude_voice/software.py`) reads
  each repository's levels from a `levels.json` that main's checks keep as the build
  artifact `quality-levels` (claude-voice's ADR-CV-009). It reads the same keys
  claude-voice's `scripts/ratchet.py` writes.
- claude-voice measures Python, with tools of its own. The app is Kotlin, in four Gradle
  modules, two of them Android modules. None of those tools reads Kotlin.
- The code-quality skill in this repository asked for the Kotlin tools to be chosen
  here before they are added.

## Options considered

- **Do nothing.** The page keeps saying "Nothing measured yet" for this repository,
  which is honest, but nobody sees coverage or complexity fall.
- **JaCoCo for coverage.** It is the older tool, but on Android it needs a report task
  written by hand for each variant, and a merged report needs another. Kover merges JVM
  and Android modules with one line per module. Not taken.
- **PMD's CPD for copied code.** CPD counts tokens, not lines. Its number would not be
  close to the bridge's measure (six lines in a row, indentation ignored), so the two
  repositories would not compare. Not taken; the bridge's own algorithm is used,
  rewritten for `.kt` files.
- **The Kotlin compiler's own complexity report.** There is none. Not taken.
- **Arcmutate's Kotlin plugin for Pitest.** It would hide mutations the Kotlin compiler
  generates, but it needs a paid licence. Not taken.

## Decision

The measures are taken by `scripts/levels.py`, run in continuous integration on every
pull request and every push to main. Main's run keeps the file as `quality-levels`.
The tools:

- line coverage: Kover, merged over `:core`, `:plugin-api`, `:app` and `:plugins:status`;
- mutants caught: Pitest with its JUnit 5 plugin, on `:core` and `:plugin-api`, under its own key
  `mutants`;
- complexity: detekt's cyclomatic count, with the highest in each file;
- lint: detekt's findings plus Android lint's errors;
- copied code: the bridge's own line-based measure, leaving out lines that say nothing
  on their own (imports, the package line, lines of brackets only).

None of these tools is part of the gate (`./gradlew check`), and nothing they measure is
committed. `quality.json` holds only the test-time budget. Comparing a pull request
with main and refusing it when it makes a measure worse (the ratchet) is a later change.

## Consequences

- There is no `mutation` key. In claude-voice it counts rules proven by a test, and the
  page reads it as "N of M rules", green only at 100 %. This repository has no rule
  rows yet. Pitest's mutants are another measure, so they go under `mutants`, which
  the page needs a label for. Pitest does not run Android unit tests, so only `:core`
  and `:plugin-api` are mutated.
- Copied code is a little lower here than claude-voice's measure would give, because
  Kotlin's closing brackets and import lists would otherwise count as copied.
- `type_errors` counts Kotlin compiler warnings, because a type error makes the build
  red and a red tree is not measured.
- Worse: three more Gradle plugins, Kover, detekt and Pitest, must be kept current, and
  the quality job adds minutes to every run.
- Without Arcmutate, Pitest also mutates code the Kotlin compiler generates, so a few
  mutants no test can catch may hold the score below 100.

## Related

- [`scripts/levels.py`](../scripts/levels.py), and its tests in `tests/test_levels.py`.
- [The code-quality skill](../.claude/skills/code-quality/SKILL.md).
- claude-voice's `scripts/ratchet.py` and ADR-CV-009.
