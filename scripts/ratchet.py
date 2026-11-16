#!/usr/bin/env python3
"""The quality ratchet: the app's code may get better or stay as it is, never worse unless a change says why.

It measures the app with scripts/levels.py and compares the levels with those main reached.
A change that makes one worse fails, unless its pull request has a line of its own

    Ratchet-Exception: coverage - the reason

naming the measure. Main keeps its levels with each of its runs as the artifact
`quality-levels` (claude-voice's ADR-CV-009); when they are missing, the base is measured
in a copy of it, with the copy's own scripts/levels.py. `quality.json` holds only the
tests' time budget, a limit set by hand.

detekt's baseline (config/detekt-baseline.xml) lists the findings the code had when
detekt became part of the gate, so that only new ones fail. It may only shrink: an entry
the base did not have fails here, unless the pull request names `detekt_baseline` the
same way.

    python3 scripts/ratchet.py check --base REF [--levels FILE] [--out FILE]

`check` reads exceptions from the environment variable RATCHET_EXCEPTIONS (continuous
integration passes the pull request's text). The shape follows claude-voice's
scripts/ratchet.py, so the two repositories are held alike.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import TYPE_CHECKING, Any

import levels

if TYPE_CHECKING:
    from collections.abc import Callable

ROOT = Path(__file__).resolve().parent.parent
FILE = ROOT / "quality.json"
BASELINE = Path("config") / "detekt-baseline.xml"
COVERAGE_WOBBLE = 0.5  # percentage points: coverage differs slightly between machines and versions
HIGHER = ("coverage", "mutants", "tests")
LOWER = ("lint_errors", "type_errors", "cycles", "layer_violations", "duplication")
DEFAULT_BUDGET = {"seconds": 30, "tolerance": 0.25}
NEW_FILE_CEILING = 15  # the most tangled function a new file may have, as in claude-voice
# A main source file: the module, then the path under the source root, which is the package path.
SOURCE = re.compile(r"^(?P<module>.+?)/src/main/(?:kotlin|java)/(?P<path>.+\.kt)$")
# Only `Ratchet-Exception: <measure> - <reason>` counts: an exception without a reason is none.
EXCEPTION = re.compile(
    r"^[ \t]*ratchet-exception:[ \t]*([a-z_]+)[ \t]+-[ \t]+\S", re.IGNORECASE | re.MULTILINE
)


def _score(value: Any) -> float:  # noqa: ANN401 - a level is a number or a killed/total pair
    if isinstance(value, dict):  # mutants: the share the tests caught
        return 100.0 * int(value["killed"]) / max(int(value["total"]), 1)
    return float(value)


def _worse(metric: str, base: Any, now: Any) -> bool:  # noqa: ANN401 - see _score
    wobble = COVERAGE_WOBBLE if metric == "coverage" else 0.0
    if metric in HIGHER:
        return _score(now) < _score(base) - wobble
    return _score(now) > _score(base)


def compare(
    base: dict[str, Any],
    now: dict[str, Any],
    allowed: set[str],
    budget: dict[str, Any],
    changed: set[str] | frozenset[str] = frozenset(),
) -> list[str]:
    """What is wrong: a measure worse than the base without an exception, or the tests over budget.

    Better levels need no recording: main measures its own.
    """
    found = []
    for metric in (*HIGHER, *LOWER):
        if metric not in base:
            continue  # a measure added since the base: nothing to compare with yet
        if _worse(metric, base[metric], now[metric]) and metric not in allowed:
            found.append(f"{metric} got worse: {base[metric]} -> {now[metric]}")
    if "mutants" not in allowed:
        found += _mutants_worse(base, now, changed)
    if "complexity" not in allowed:
        for file, score in now["complexity"].items():
            before = base.get("complexity", {}).get(file)
            if before is not None and score > before:
                found.append(f"complexity of {file} got worse: {before} -> {score}")
            if before is None and score > NEW_FILE_CEILING:
                found.append(
                    f"{file} is new and has a function of complexity {score}; keep it at {NEW_FILE_CEILING}"
                )
    limit = budget["seconds"] * (1 + budget["tolerance"])
    if now["test_seconds"] > limit:
        found.append(
            f"test_seconds is {now['test_seconds']}, over the budget of {budget['seconds']}"
            f" (+{budget['tolerance']:.0%})"
        )
    return found


def _mutants_worse(
    base: dict[str, Any], now: dict[str, Any], changed: set[str] | frozenset[str]
) -> list[str]:
    """A module that fell or is no longer measured, or a new survivor in a file the change touched.

    A module the base did not measure starts its own baseline. `Ratchet-Exception: mutants`
    lets all three through, as it does the total.
    """
    return _modules_worse(base, now) + _survivors_worse(base, now, changed)


def _modules_worse(base: dict[str, Any], now: dict[str, Any]) -> list[str]:
    found = []
    before, after = base.get("mutants_by_module", {}), now.get("mutants_by_module", {})
    for module, counts in before.items():
        if module not in after:
            found.append(f"mutants of {module} are no longer measured")
        elif _score(after[module]) < _score(counts):
            was, is_now = _score(counts), _score(after[module])
            found.append(f"mutants of {module} got worse: {was:.1f}% -> {is_now:.1f}%")
    return found


def _survivors_worse(
    base: dict[str, Any], now: dict[str, Any], changed: set[str] | frozenset[str]
) -> list[str]:
    if "survivors" not in base:
        return []
    found = []
    for file in sorted(changed):
        was, now_left = base["survivors"].get(file, 0), now.get("survivors", {}).get(file, 0)
        if now_left > was:
            found.append(f"a mutant survives in {file}, which this change touched: {was} -> {now_left}")
    return found


def changed_sources(paths: list[str]) -> set[str]:
    """The Kotlin sources among `paths`, by package path as survivors are: `core/org/x/Pairing.kt`."""
    found = set()
    for p in paths:
        match = SOURCE.match(p)
        if match:
            found.add(f"{match['module']}/{match['path']}")
    return found


def exceptions(text: str) -> set[str]:
    """The measures a pull request allows to get worse, each on a `Ratchet-Exception:` line."""
    return {m.lower() for m in EXCEPTION.findall(text or "")}


def _ids(baseline: str) -> set[str]:
    return {e.text or "" for e in ET.fromstring(baseline).iter("ID")}  # noqa: S314 - our own file


def baseline_added(base: str | None, now: str) -> list[str]:
    """The entries in detekt's baseline that the base did not have, which the ratchet refuses."""
    return sorted(_ids(now) - (_ids(base) if base else set()))


def budget(path: Path) -> dict[str, Any]:
    """The tests' time budget, the one thing `quality.json` keeps."""
    try:
        return dict(json.loads(path.read_text())["test_budget"])
    except (OSError, ValueError, KeyError, TypeError):
        return dict(DEFAULT_BUDGET)


def base_levels(kept: Path | None, measure_base: Callable[[], dict[str, Any]]) -> dict[str, Any]:
    """Main's kept levels for the base, or, when they are missing, the base measured."""
    try:
        found = json.loads(kept.read_text()) if kept else None
    except (OSError, ValueError):
        found = None
    if isinstance(found, dict) and "coverage" in found:
        return found
    return measure_base()


def _git(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=False)  # noqa: S603, S607


def measure_at(ref: str) -> dict[str, Any]:
    """Measure `ref` in a copy of it, with the copy's own scripts/levels.py.

    The copy's own script, because the build it measures is the copy's: its Gradle tasks
    are the ones that script knows. A base without the script has no levels to compare.
    """
    with tempfile.TemporaryDirectory() as tmp:
        copy, out = Path(tmp) / "base", Path(tmp) / "levels.json"
        added = _git("worktree", "add", "--detach", str(copy), ref)
        if added.returncode:
            sys.exit(f"Cannot check out {ref} to measure it:\n{added.stderr}")
        try:
            script = copy / "scripts" / "levels.py"
            if not script.exists():
                print(f"{ref} has no scripts/levels.py; there is nothing to compare with.")  # noqa: T201
                return {}
            measured = subprocess.run(  # noqa: S603 - fixed arguments
                [sys.executable, str(script), "--out", str(out)],
                cwd=copy,
                capture_output=True,
                text=True,
                check=False,
            )
            if measured.returncode:
                sys.exit(f"Measuring {ref} failed:\n{measured.stdout[-2000:]}{measured.stderr[-2000:]}")
            return dict(json.loads(out.read_text()))
        finally:
            _git("worktree", "remove", "--force", str(copy))


def check(base_ref: str, kept: Path | None, out: Path | None) -> list[str]:
    """Measure the tree, compare it with the base, and say what got worse."""
    base = base_levels(kept, lambda: measure_at(base_ref))
    now = levels.measure()
    if out:
        out.write_text(json.dumps(now, indent=2, sort_keys=True) + "\n")
    allowed = exceptions(os.environ.get("RATCHET_EXCEPTIONS", ""))
    touched = _git("diff", "--name-only", f"{base_ref}...HEAD").stdout.split()
    found = compare(base, now, allowed, budget(FILE), changed_sources(touched))
    shown = _git("show", f"{base_ref}:{BASELINE.as_posix()}")
    added = baseline_added(shown.stdout if shown.returncode == 0 else None, (ROOT / BASELINE).read_text())
    if added and "detekt_baseline" not in allowed:
        found.append("detekt's baseline grew; fix these instead: " + ", ".join(added))
    return found


def main() -> int:
    """Run `check`, print what got worse, and fail if anything did."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("action", choices=("check",))
    parser.add_argument("--base", required=True, help="the git ref to compare with, usually main")
    parser.add_argument("--levels", type=Path, help="main's kept levels for the base, if there are any")
    parser.add_argument("--out", type=Path, help="where to write the levels measured now")
    args = parser.parse_args()
    found = check(args.base, args.levels, args.out)
    for problem in found:
        print(problem, file=sys.stderr)  # noqa: T201
    if not found:
        print("The ratchet holds: nothing got worse than the base.")  # noqa: T201
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
