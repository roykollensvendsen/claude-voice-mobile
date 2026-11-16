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
- mutants caught: Pitest with its JUnit 5 plugin, on `:core` and `:plugin-api`, leaving
  out the data-only classes in `config/pitest-data-only.txt`, whose every mutant is in code
  the compiler wrote (a test refuses a class there that gains logic), and skipping calls
  to `kotlin.jvm.internal`, the null checks the compiler inserts, under its own key
  `mutants`, with each module's counts under `mutants_by_module` and the mutants no test
  caught, per source file, under `survivors`. Pitest runs on every core, and keeps its
  history of earlier results in the CI cache, never in git, so a run mutates again only
  what changed. Each run keeps Pitest's HTML and XML reports as the artifact
  `pitest-reports`, so a survivor can be read without running anything;
- complexity: detekt's cyclomatic count, with the highest in each file;
- lint: detekt's findings plus Android lint's errors;
- copied code: the bridge's own line-based measure, leaving out lines that say nothing
  on their own (imports, the package line, lines of brackets only).

Nothing they measure is committed. `quality.json` holds only the test-time budget.
`scripts/ratchet.py` compares each run's levels with main's and fails when one got
worse, unless the pull request names it on a `Ratchet-Exception:` line, as in
claude-voice. For mutants it also refuses a module whose share caught fell, so a module
that gains much cannot hide a drop in another, and a new survivor in a source file the
pull request touched. A module the base did not measure starts its own baseline.
`Ratchet-Exception: mutants` lets all three through. Of the tools, only detekt is part of the gate (`./gradlew check`), with a
baseline of the findings the code had when it joined. The ratchet refuses a new entry
in that baseline, so it can only shrink. The ratchet compares only on a pull request; main's own
run measures and keeps its levels, because the exception lines of the pull request that
landed are not there to read.

The baseline has two limits. A baseline entry is a rule plus the function's signature, so
a second finding of the same rule in a function already listed adds no entry and the gate
lets it through; the measuring run counts every finding, and the ratchet refuses the
higher `lint_errors`, so the two layers are both needed. And renaming a listed function
makes its entry look new, so an honest rename needs a `detekt_baseline` exception.

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
