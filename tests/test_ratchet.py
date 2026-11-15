"""What the ratchet refuses and lets through, for the app's code-health levels."""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "scripts"))

import ratchet

BUDGET = {"seconds": 30, "tolerance": 0.25}


def levels(**changes):
    base = {
        "coverage": 60.0,
        "mutants": {"killed": 42, "total": 45},
        "tests": 32,
        "lint_errors": 22,
        "type_errors": 0,
        "cycles": 0,
        "layer_violations": 0,
        "duplication": 0.0,
        "complexity": {"core/Tailnet.kt": 6},
        "test_seconds": 5.0,
        "test_budget": None,
    }
    return {**base, **changes}


def test_levels_as_good_as_the_base_pass():
    assert ratchet.compare(levels(), levels(), set(), BUDGET) == []


def test_a_measure_that_gets_worse_fails_the_check():
    found = ratchet.compare(levels(), levels(tests=31, lint_errors=23), set(), BUDGET)
    assert found == ["tests got worse: 32 -> 31", "lint_errors got worse: 22 -> 23"]


def test_fewer_mutants_caught_is_worse_even_with_more_made():
    assert ratchet.compare(levels(), levels(mutants={"killed": 42, "total": 50}), set(), BUDGET)


def test_coverage_may_wobble_half_a_point():
    assert ratchet.compare(levels(), levels(coverage=59.6), set(), BUDGET) == []
    assert ratchet.compare(levels(), levels(coverage=59.4), set(), BUDGET)


def test_an_exception_line_lets_that_measure_get_worse():
    allowed = ratchet.exceptions("Some text.\nRatchet-Exception: tests - the old tests were wrong\n")
    assert allowed == {"tests"}
    assert ratchet.compare(levels(), levels(tests=30), allowed, BUDGET) == []


def test_a_file_whose_worst_function_gets_worse_fails():
    found = ratchet.compare(levels(), levels(complexity={"core/Tailnet.kt": 7}), set(), BUDGET)
    assert found == ["complexity of core/Tailnet.kt got worse: 6 -> 7"]


def test_a_new_file_may_not_start_above_the_ceiling():
    now = levels(complexity={"core/Tailnet.kt": 6, "core/New.kt": ratchet.NEW_FILE_CEILING + 1})
    assert len(ratchet.compare(levels(), now, set(), BUDGET)) == 1
    now = levels(complexity={"core/Tailnet.kt": 6, "core/New.kt": ratchet.NEW_FILE_CEILING})
    assert ratchet.compare(levels(), now, set(), BUDGET) == []


def test_tests_over_their_budget_and_tolerance_fail():
    assert ratchet.compare(levels(), levels(test_seconds=37.5), set(), BUDGET) == []
    assert ratchet.compare(levels(), levels(test_seconds=37.6), set(), BUDGET)


def test_a_measure_the_base_did_not_have_is_not_compared():
    base = levels()
    del base["mutants"]
    assert ratchet.compare(base, levels(mutants={"killed": 0, "total": 9}), set(), BUDGET) == []


BASELINE = """<?xml version="1.0" ?>
<SmellBaseline>
  <ManuallySuppressedIssues/>
  <CurrentIssues>
{}
  </CurrentIssues>
</SmellBaseline>
"""


def _baseline(*ids):
    return BASELINE.format("\n".join(f"    <ID>{i}</ID>" for i in ids))


def test_the_detekt_baseline_may_only_shrink():
    base = _baseline("MagicNumber:A.kt$1", "MaxLineLength:B.kt$x")
    assert ratchet.baseline_added(base, _baseline("MagicNumber:A.kt$1")) == []
    assert ratchet.baseline_added(base, _baseline("MagicNumber:A.kt$1", "ReturnCount:C.kt$f")) == [
        "ReturnCount:C.kt$f"
    ]


def test_with_no_baseline_on_the_base_every_entry_is_new():
    assert ratchet.baseline_added(None, _baseline("MagicNumber:A.kt$1")) == ["MagicNumber:A.kt$1"]


def test_main_kept_levels_are_used_when_there_are_some(tmp_path):
    kept = tmp_path / "levels.json"
    kept.write_text('{"coverage": 1.0}')
    assert ratchet.base_levels(kept, lambda: {"coverage": 2.0}) == {"coverage": 1.0}


def test_when_main_kept_no_levels_the_base_is_measured(tmp_path):
    assert ratchet.base_levels(tmp_path / "missing.json", lambda: {"coverage": 2.0}) == {"coverage": 2.0}
    assert ratchet.base_levels(None, lambda: {"coverage": 2.0}) == {"coverage": 2.0}
