#!/usr/bin/env python3
"""Measure the app's code health for the bridge's code-health page.

Writes one levels.json in the keys claude-voice's scripts/ratchet.py writes, so the page
reads both repositories alike. Continuous integration runs it on every push to main and
keeps the file with that run as the artifact `quality-levels`; the numbers are never
committed. `quality.json` holds only the test-time budget, a limit set by hand.

Two keys are measured more narrowly than in claude-voice:

- `mutants` replaces `mutation`. In claude-voice `mutation` counts rules proven by a
  test, and this repository has no rule rows yet; `mutants` is Pitest's mutants caught,
  in :core and :plugin-api only, because Pitest cannot run Android unit tests.
- `duplication` leaves out lines that say nothing on their own: imports, the package
  line, and lines of brackets only.
- `type_errors` counts Kotlin compiler warnings, because a build with a type error is
  red and never measured.

    python3 scripts/levels.py --out levels.json
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULES = ("core", "plugin-api", "app", "plugins/status")
MUTATED = ("core", "plugin-api")  # Pitest cannot run Android unit tests
PREFIX = "org.claudevoice.mobile"
BLOCK = 6  # lines in a row that count as a copied block, as in claude-voice
# Lines that say nothing on their own, left out of the copied-code measure.
NOISE = re.compile(r"^(?:import |package )|^[{}()\[\];,.]+$")
COMPLEXITY_RULE = "detekt.CyclomaticComplexMethod"
COMPLEXITY = re.compile(r"\(complexity: (\d+)\)")
# Where each test task above writes its results; other variants' results are left out.
RESULTS = (
    ("core", "test"),
    ("plugin-api", "test"),
    ("app", "testDebugUnitTest"),
    ("plugins/status", "testDebugUnitTest"),
)
TEST_TASKS = (":core:test", ":plugin-api:test", ":app:testDebugUnitTest", ":plugins:status:testDebugUnitTest")


def _parse(path: Path) -> ET.Element:
    return ET.parse(path).getroot()  # noqa: S314 - our own build's reports


def coverage(report: Path) -> float:
    """The share of lines run by the tests, in percent, from Kover's merged XML report."""
    line = next(c for c in _parse(report).findall("counter") if c.get("type") == "LINE")
    missed, covered = int(line.get("missed", "0")), int(line.get("covered", "0"))
    return round(100 * covered / (missed + covered), 2) if missed + covered else 0.0


def mutants(reports: list[Path]) -> dict[str, int]:
    """Mutants the tests caught, of all made, over Pitest's XML reports."""
    # A module whose classes are all data-only has no mutants, and Pitest may write no report.
    made = [m for r in reports if r.exists() for m in _parse(r).findall("mutation")]
    return {"killed": sum(m.get("detected") == "true" for m in made), "total": len(made)}


def passed_tests(folders: list[Path]) -> int:
    """Tests that passed, over every JUnit result file under `folders`."""
    total = 0
    for folder in folders:
        for path in folder.rglob("TEST-*.xml"):
            suite = _parse(path)
            total += int(suite.get("tests", "0")) - sum(
                int(suite.get(k, "0")) for k in ("failures", "errors", "skipped")
            )
    return total


def _short(path: str, root: Path) -> str:
    """`core/src/main/kotlin/org/x/Tailnet.kt` as `core/Tailnet.kt`."""
    rel = Path(path).relative_to(root).as_posix()
    module = rel.split("/src/")[0]
    return f"{module}/{Path(path).name}"


def complexity(report: Path, root: Path) -> dict[str, int]:
    """The most tangled function in each file, by detekt's cyclomatic count."""
    out: dict[str, int] = {}
    for file in _parse(report).findall("file"):
        scores = [
            int(m[1])
            for e in file.findall("error")
            if e.get("source") == COMPLEXITY_RULE and (m := COMPLEXITY.search(e.get("message", "")))
        ]
        if scores:
            out[_short(file.get("name", ""), root)] = max(scores)
    return out


def detekt_findings(report: Path) -> int:
    """Findings from detekt, leaving out the complexity rule, which is set to report every function."""
    return sum(e.get("source") != COMPLEXITY_RULE for e in _parse(report).iter("error"))


def android_lint_errors(reports: list[Path]) -> int:
    """Android lint's errors and fatal issues; warnings are left out."""
    return sum(
        i.get("severity") in {"Error", "Fatal"}
        for r in reports
        if r.exists()
        for i in _parse(r).iter("issue")
    )


def compiler_warnings(output: str) -> int:
    """The warnings Kotlin's compiler printed, one `w:` line each."""
    return sum(line.startswith("w: ") for line in output.splitlines())


def _sources(folders: list[Path]) -> list[Path]:
    return sorted(p for f in folders for p in f.rglob("*.kt"))


def _meaningful(path: Path) -> list[str]:
    """The file's lines with indentation collapsed, leaving out blank lines and NOISE."""
    lines = (" ".join(line.split()) for line in path.read_text(encoding="utf-8").splitlines())
    return [line for line in lines if line and not NOISE.search(line)]


def duplication(folders: list[Path]) -> float:
    """The share of lines, in percent, in a repeated block.

    A block is BLOCK or more lines in a row appearing more than once, within a file or across
    files, ignoring indentation: claude-voice's measure, except that lines saying nothing on
    their own (NOISE) are left out first.
    """
    files = {p: _meaningful(p) for p in _sources(folders)}
    where: dict[tuple[str, ...], list[tuple[Path, int]]] = {}
    for path, lines in files.items():
        for i in range(len(lines) - BLOCK + 1):
            where.setdefault(tuple(lines[i : i + BLOCK]), []).append((path, i))
    copied = {
        (p, j) for spots in where.values() if len(spots) > 1 for p, i in spots for j in range(i, i + BLOCK)
    }
    total = sum(len(lines) for lines in files.values())
    return round(100 * len(copied) / total, 2) if total else 0.0


def _package_graph(folders: list[Path], prefix: str) -> dict[str, set[str]]:
    graph: dict[str, set[str]] = {}
    for path in _sources(folders):
        text = path.read_text(encoding="utf-8")
        own = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        if not own:
            continue
        used = {
            ".".join(name.split(".")[:-1])
            for name in re.findall(r"^import\s+([\w.]+)", text, re.MULTILINE)
            if name.startswith(prefix + ".")
        }
        graph.setdefault(own[1], set()).update(used - {own[1]})
    for targets in list(graph.values()):
        for target in targets:
            graph.setdefault(target, set())
    return graph


def cycles(folders: list[Path], prefix: str = PREFIX) -> int:
    """How many groups of packages import each other in a circle (Tarjan's components)."""
    graph = _package_graph(folders, prefix)
    index: dict[str, int] = {}
    low: dict[str, int] = {}
    stack: list[str] = []
    groups = 0

    def visit(node: str) -> None:
        nonlocal groups
        index[node] = low[node] = len(index)
        stack.append(node)
        for nxt in graph[node]:
            if nxt not in index:
                visit(nxt)
                low[node] = min(low[node], low[nxt])
            elif nxt in stack:
                low[node] = min(low[node], index[nxt])
        if low[node] == index[node]:
            size = len(stack) - stack.index(node)
            del stack[-size:]
            groups += size > 1

    for name in sorted(graph):
        if name not in index:
            visit(name)
    return groups


def layer_violations(output: str, *, ok: bool) -> int:
    """The module edges checkLayers refused: each is listed on its own indented line."""
    if ok:
        return 0
    after = output.split("are broken:", 1)[-1]
    return sum(1 for line in after.splitlines() if line.startswith("  ") and line.strip())


def _gradle(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # noqa: S603 - fixed arguments
        [str(ROOT / "gradlew"), "--console=plain", *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def _must(run: subprocess.CompletedProcess[str], what: str) -> subprocess.CompletedProcess[str]:
    if run.returncode:
        sys.exit(f"{what} failed; only a green tree is measured.\n{run.stdout[-3000:]}{run.stderr[-3000:]}")
    return run


KEYS = (
    "coverage",
    "mutants",
    "tests",
    "lint_errors",
    "type_errors",
    "cycles",
    "layer_violations",
    "duplication",
    "complexity",
    "test_seconds",
    "test_budget",
)


def measure() -> dict[str, object]:
    """Every measure, from the tree as it is."""
    compiled = _must(_gradle("--rerun-tasks", "compileTestKotlin", "compileDebugUnitTestKotlin"), "Compiling")
    started = time.monotonic()
    _must(_gradle(*(a for task in TEST_TASKS for a in (task, "--rerun"))), "The tests")
    seconds = round(time.monotonic() - started, 1)
    reports = ("koverXmlReport", "detektMeasure", *(f":{m}:pitest" for m in MUTATED), "lintDebug")
    _must(_gradle(*reports), "Measuring")
    layers = _gradle("checkLayers")
    sources = [ROOT / m / "src" / "main" for m in MODULES]
    detekt = ROOT / "build" / "reports" / "detekt" / "measure.xml"
    lint = [ROOT / m / "build" / "reports" / "lint-results-debug.xml" for m in MODULES]
    levels = {
        "coverage": coverage(ROOT / "build" / "reports" / "kover" / "report.xml"),
        "mutants": mutants([ROOT / m / "build" / "reports" / "pitest" / "mutations.xml" for m in MUTATED]),
        "tests": passed_tests([ROOT / m / "build" / "test-results" / t for m, t in RESULTS]),
        "lint_errors": detekt_findings(detekt) + android_lint_errors(lint),
        "type_errors": compiler_warnings(compiled.stdout + compiled.stderr),
        "cycles": cycles(sources),
        "layer_violations": layer_violations(layers.stdout + layers.stderr, ok=layers.returncode == 0),
        "duplication": duplication(sources),
        "complexity": complexity(detekt, ROOT),
        "test_seconds": seconds,
        "test_budget": None,
    }
    assert tuple(levels) == KEYS  # noqa: S101 - the keys the bridge is told about
    return levels


def main() -> None:
    """Measure, and write the levels where `--out` says."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    levels = measure()
    args.out.write_text(json.dumps(levels, indent=2, sort_keys=True) + "\n")
    print(json.dumps(levels, indent=2, sort_keys=True))  # noqa: T201 - the run's log


if __name__ == "__main__":
    main()
