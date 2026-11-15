---
name: code-quality
description: Make this repository's measures on the code-health page green, and keep them there - coverage, rules proven by a test, the most tangled function, layers, copied code, the time the tests take and the checks on main. Use when the page shows a measure in amber or red or as "Nothing measured yet", before a pull request that adds or splits code, when someone asks how good the code is or how to raise a number, or when a measure seems impossible to meet and an exemption is tempting.
---

# Keeping the code-health page green

The claude-voice bridge has a software page (`http://127.0.0.1:8811/ui/`, read
from `GET /api/v1/health/software`). It shows one row per repository and one
cell per measure. The numbers come from the levels main's checks keep after
every merge, so a pull request turns a cell green only once it has landed. This
skill is the routine for this repository: read the page, measure the same thing
locally, fix the worst cell test-first, land it.

## 1. What the page measures, and when it is green

| Cell | Green | Amber | Red |
| --- | --- | --- | --- |
| Test coverage | 80 % or more | 60 - 80 % | under 60 % |
| Rules proven by a test (mutations killed) | every one | 90 % or more | fewer |
| Most tangled function (McCabe) | 15 or less | up to 25 | above 25 |
| Modules in their layers | 0 imports out of order | | any |
| Copied code | under 3 % of lines | up to 6 % | more |
| Lint and type errors | 0 | | any |
| Import cycles | 0 | any | |
| Time the tests take | inside the budget | inside budget plus tolerance | over |
| Checks on main | passing | running | failed |

The limits live in the bridge, not here: `MEASURES` in claude-voice's
`src/claude_voice/software.py`. Read them there if this table and the page ever
disagree, and fix the table.

A cell can also read **exempt** (section 4). Exempt is never green, and nothing
in this skill makes a cell exempt by itself.

## 2. What this repository records today

The app's measures, from the levels main's checks keep. The `quality` job in
`.github/workflows/checks.yml` runs `scripts/levels.py` on every run, and main's
run keeps its `levels.json` as the artifact `quality-levels` (ADR-CV-009 in
claude-voice). The tools are chosen in
[ADR-CVM-004](../../../decisions/ADR-CVM-004-measuring-the-apps-code-health.md).
`quality.json` holds only the test-time budget, and nothing measured is
committed.

The repository has two halves. The Kotlin half is the app: the Gradle modules
`:core`, `:plugin-api`, `:app` and `:plugins:*`, with their layer rules in
`build-logic/`. The Python half is the tooling under `src/`, `scripts/` and
`tests/`. The page shows the Kotlin half only.

| Cell | Kotlin half (on the page) | Python half (not on the page) |
| --- | --- | --- |
| Test coverage | Kover, lines, merged over the four modules | printed by `pytest --cov`, not kept |
| Rules proven by a test | not measured: no rule rows yet; Pitest's mutants caught in `:core` and `:plugin-api` go under their own key, `mutants` | `scripts/mutate.py`; `scripts/mutations.toml` has no rows yet |
| Most tangled function | detekt's cyclomatic count, the highest in each file | ruff's `C901`, so above 10 fails lint |
| Lint and type errors | detekt's findings and Android lint's errors; Kotlin compiler warnings as type errors | ruff and mypy fail the checks |
| Modules in their layers | `checkLayers`, one per refused edge | not measured |
| Import cycles | groups of packages importing each other | not measured |
| Copied code | the bridge's measure (six lines in a row, indentation ignored), leaving out imports, the package line and lines of brackets only | not measured |
| Time the tests take | the four test tasks, against `quality.json`'s budget | not measured |
| Checks on main | the latest run, which the bridge asks GitHub for | the same run |

The ratchet, `scripts/ratchet.py`, runs in the same job. It fails a pull
request whose measure got worse than main's, unless the pull request has a line
of its own, `Ratchet-Exception: <measure> - the reason`. detekt is part of the
gate. The findings the code already had are listed in
`config/detekt-baseline.xml`, and the ratchet refuses a new entry there
(exception name `detekt_baseline`). So fix a new finding rather than
regenerating the baseline. When you fix one, remove its line too. Renaming or re-signing a function that has a line there
makes the line look new. That is an honest change: say so on a
`Ratchet-Exception: detekt_baseline - renamed f to g` line.

## 3. Measure it locally

Run these from the repository root after `uv sync --all-extras`. The mutation
run goes in the background; never put a time limit on it, because a cut-off run
reports rules as unproven that are not.

<!-- not run: examples of measuring one cell at a time -->
```
uv run pytest -q --cov                         # coverage, with the lines no test runs
uv run pytest -q --durations=15                # the slowest tests
uv run ruff check . && uv run mypy src         # lint and type errors
uv run python scripts/mutate.py                # each rule switched off in turn
uv run python scripts/mutate.py --write evidence/tests/rule-mutations.md   # the same, kept as evidence
```

For every Python function's complexity score, not only the ones over ruff's
limit:

<!-- not run: lists every function with its score, and exits 1 on purpose -->
```
uv run ruff check --select C901 --config 'lint.mccabe.max-complexity = 0' --output-format concise .
```

Each line reads `` `name` is too complex (N > 0) ``; the highest N is the cell.

The app's gate is one Gradle command. It needs JDK 17 in `JAVA_HOME` and the
Android SDK in `ANDROID_HOME` (or `sdk.dir` in an untracked `local.properties`).
It runs the layer rules, their own tests, and every module's tests and lint.
A cold cache takes some minutes, so run it in the background too.

<!-- not run: needs JDK 17 and the Android SDK, and takes minutes on a cold cache -->
```
./gradlew check --console=plain                # the app's whole gate, as CI runs it
./gradlew checkLayers                          # only the layer rules
python3 scripts/levels.py --out levels.json    # every measure the page shows, as CI takes them
python3 scripts/ratchet.py check --base origin/main   # the same, compared with main as CI does
```

## 4. Fix the worst cell, test-first

Work on one cell per pull request, the reddest first. Everything here follows
CONTRIBUTING.md: a failing test before the code, one logical change per pull
request, commits through the `write-commit` skill.

**Checks on main red.** Fix that before anything else: every other number is
measured by those checks, so while they fail the page stops moving.

**Coverage under 80.** Open the lines `pytest --cov` lists as missing and ask
what the person using the code would notice if each were wrong. Write a test of
that behaviour, through the module's public functions, and see it red. For code
that already exists, break the line by hand (or with `mutate.py`), watch the
test fail, then put the line back. A test that passed the first time it ran
proves nothing. Never test a private helper just to lift the number. Never mark
code as excluded from coverage to get past the limit; that is an exemption, and
section 5 applies.

**A rule nobody proves.** `mutate.py` names each rule whose removal no test
noticed. A rule is a line marked `# RULE: what it says`. It needs a row in
`scripts/mutations.toml` and a test named in the rule's own words
(`# RULE: a spoken reply is capped` wants `test_a_spoken_reply_is_capped`).
`tests/test_rules_have_evidence.py` refuses a rule missing either. Write that
test, run the mutation, see that very test go red, and keep the row. Never
loosen a row or point it at something easier to make the count go up; if a row
has to change, the pull request says why.

**A function above 15.** Split it without changing what it does:

1. Run the module's tests and see them green. If nothing covers the function's
   branches yet, write those tests first, as a pull request of their own.
2. Pull one decision out at a time: a guard clause that returns early, a
   lookup table in place of a chain of `if`s, one helper per branch of a
   `match`, a loop body as a function. Each helper does one thing and is named
   for it.
3. Run the same tests and see them green again, unchanged. A split that needs
   a test edited has changed behaviour, and is not a split.
4. Commit it as `refactor:`, with no behaviour change in the same commit.

A nested function counts on its own, not towards the function around it. So
hiding branches in a closure is not a fix the page will reward; give the helper
a name at module level. In the Python half ruff already refuses anything above
10, which is stricter than the page.

**An import out of its layer, or a cycle.** In the app the layers are
ADR-CVM-003's, written in `build-logic/src/main/kotlin/LayerRules.kt`. The core
never depends on a plugin, a plugin depends only on `:plugin-api`, and no plugin
depends on another. Move the shared code down into a module both may use, or
pass the thing in through the plugin interface. A new module is refused until it
has a layer there, in the same pull request. The Python half has no layers yet.

**Copied code at 3 % or more.** A block counts when six or more lines in a row
appear more than once, ignoring indentation. Put the block in one function both
places call, with the tests green before and after, as `refactor:`. Copied
tests count too; a shared fixture or helper in the test folder is the fix.

**Tests over their budget.** Find the slowest with `--durations=15`. The usual
cause is a real wait: a sleep, a timeout, a subprocess. Make the wait
injectable and pass a fake clock or a short timeout in the test. Setting or
raising the budget is the owner's decision, not a fix.

**Lint or type errors.** Fix them as they are reported.

Before you push, run the gates in CONTRIBUTING.md after your last edit.

## 5. Exemptions

Sometimes a cell should stay outside its limit: a function that is one long
match on a tool's name, a generated table, a test that has to wait for real
hardware. Such a cell may be marked exempt, and only like this:

- **A written reason**, saying why meeting the limit would make the code worse,
  not that it would take long.
- **Roy's yes.** Ask through the manager session (claude-voice-work) with the
  exact entry and your recommendation. Never add one on your own judgement, and
  never count silence as a yes.
- **In this repository's own `quality.toml`**, in a pull request of its own,
  one entry per measure, with the measure's id as the page names it:

<!-- not run: an example entry -->
```toml
[[exempt]]
measure = "complexity"
reason = "the tool table is one long match on the tool's name"
approved = "Roy, 2026-11-11, in pull request #N"
```

The bridge reads `quality.toml` from this repository's clone. The page then
shows that cell as exempt, with the reason, never as green. An entry without
both a reason and an approval is ignored. A measure back inside its limit is
green again whatever the file says, so delete the entry then. The ids are
`coverage`, `mutation`, `tests`, `problems`, `cycles`, `layers`,
`duplication`, `complexity` and `test_time`.

## 6. Land it

Push your own branch, open the pull request, and once every check is green
merge it with `gh pr merge --rebase`. Then wait for main's checks to finish:
only their kept levels move the page, and the page itself refreshes every ten
minutes. Look at the cell again; if it is still not green, start again at
section 4 with what it now says.
