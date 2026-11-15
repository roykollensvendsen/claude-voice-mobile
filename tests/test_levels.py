"""How the app's measures are read from the tools' reports, for the code-health page.

Each reader gets a small report in the shape its tool writes, so a tool changing its
format turns a test red here before the page shows a wrong number.
"""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "scripts"))

import levels

ROOT = pathlib.Path("/repo")


def test_coverage_is_the_share_of_lines_over_every_module(tmp_path):
    report = tmp_path / "report.xml"
    report.write_text(
        '<report name="x"><package name="a">'
        '<counter type="LINE" missed="1" covered="9"/></package>'
        '<counter type="INSTRUCTION" missed="5" covered="5"/>'
        '<counter type="LINE" missed="66" covered="94"/></report>'
    )
    assert levels.coverage(report) == 58.75


def test_mutation_counts_killed_and_timed_out_as_caught(tmp_path):
    report = tmp_path / "mutations.xml"
    report.write_text(
        "<mutations>"
        "<mutation detected='true' status='KILLED'/>"
        "<mutation detected='true' status='TIMED_OUT'/>"
        "<mutation detected='false' status='SURVIVED'/>"
        "<mutation detected='false' status='NO_COVERAGE'/>"
        "</mutations>"
    )
    assert levels.mutation(report) == {"killed": 2, "total": 4}


def test_tests_are_those_that_passed_in_every_result_file(tmp_path):
    (tmp_path / "a").mkdir()
    (tmp_path / "a" / "TEST-one.xml").write_text('<testsuite tests="5" failures="1" errors="0" skipped="1"/>')
    (tmp_path / "TEST-two.xml").write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
    assert levels.passed_tests([tmp_path]) == 6


DETEKT = """<checkstyle>
<file name="/repo/core/src/main/kotlin/org/x/Tailnet.kt">
<error severity="warning" source="detekt.CyclomaticComplexMethod"
 message="The function state appears to be too complex based on Cyclomatic Complexity (complexity: 7). x"/>
<error severity="warning" source="detekt.CyclomaticComplexMethod"
 message="The function other appears to be too complex based on Cyclomatic Complexity (complexity: 3). x"/>
<error severity="warning" source="detekt.MagicNumber" message="This expression contains a magic number."/>
</file>
<file name="/repo/app/src/main/kotlin/org/x/App.kt">
<error severity="warning" source="detekt.MaxLineLength" message="Line too long."/>
</file>
</checkstyle>"""


def test_complexity_is_the_worst_function_in_each_file(tmp_path):
    report = tmp_path / "detekt.xml"
    report.write_text(DETEKT)
    assert levels.complexity(report, ROOT) == {"core/Tailnet.kt": 7}


def test_detekt_findings_count_as_lint_except_the_complexity_measure(tmp_path):
    report = tmp_path / "detekt.xml"
    report.write_text(DETEKT)
    assert levels.detekt_findings(report) == 2


def test_android_lint_counts_errors_and_fatal_issues_only(tmp_path):
    report = tmp_path / "lint-results-debug.xml"
    report.write_text(
        '<issues><issue severity="Error"/><issue severity="Fatal"/><issue severity="Warning"/></issues>'
    )
    assert levels.android_lint_errors([report, tmp_path / "missing.xml"]) == 2


def test_compiler_warnings_are_the_lines_kotlin_marks_w():
    output = "> Task :core:compileKotlin\nw: file:///repo/a.kt:3:5 Unused variable\ne: no\nw: another\n"
    assert levels.compiler_warnings(output) == 2


def test_duplication_is_the_share_of_lines_in_a_repeated_block(tmp_path):
    block = [f"val a{i} = {i}" for i in range(levels.BLOCK)]
    (tmp_path / "One.kt").write_text("\n".join(["package x", *block, "fun one() = 1"]) + "\n")
    (tmp_path / "Two.kt").write_text("\n".join(["package y", *("    " + line for line in block)]) + "\n\n")
    # 12 of the 15 non-blank lines sit in the block seen twice, indentation ignored.
    assert levels.duplication([tmp_path]) == 80.0


def test_a_cycle_is_a_group_of_packages_importing_each_other(tmp_path):
    files = {
        "A.kt": "package org.p.a\nimport org.p.b.B\n",
        "B.kt": "package org.p.b\nimport org.p.a.A\nimport org.p.c.C\n",
        "C.kt": "package org.p.c\nimport java.io.File\n",
        "D.kt": "package org.p.d\nimport org.p.d.Other\n",
    }
    for name, text in files.items():
        (tmp_path / name).write_text(text)
    assert levels.cycles([tmp_path], prefix="org.p") == 1


def test_a_layer_breach_is_each_line_checklayers_lists():
    failed = (
        "FAILURE: Build failed\n* What went wrong:\n"
        "The layer rules of ADR-CVM-003 are broken:\n  :core -> :app\n  :plugin-api -> :core\n\n* Try:\n"
    )
    assert levels.layer_violations(failed, ok=False) == 2
    assert levels.layer_violations("BUILD SUCCESSFUL", ok=True) == 0
