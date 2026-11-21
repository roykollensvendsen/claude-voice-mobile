"""How the app's measures are read from the tools' reports, for the code-health page.

Each reader gets a small report in the shape its tool writes, so a tool changing its
format turns a test red here before the page shows a wrong number.
"""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "scripts"))

import levels
import pytest
import test_pitest_data_only as guard

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


def test_mutants_count_killed_and_timed_out_as_caught_over_every_report(tmp_path):
    core = tmp_path / "core.xml"
    core.write_text(
        "<mutations>"
        "<mutation detected='true' status='KILLED'/>"
        "<mutation detected='true' status='TIMED_OUT'/>"
        "<mutation detected='false' status='SURVIVED'/>"
        "<mutation detected='false' status='NO_COVERAGE'/>"
        "</mutations>"
    )
    api = tmp_path / "api.xml"
    api.write_text("<mutations><mutation detected='true' status='KILLED'/></mutations>")
    assert levels.mutants([core, api], may_be_missing=set()) == {"killed": 3, "total": 5}


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
    # 12 of the 13 lines that say something sit in the block seen twice, indentation ignored.
    assert levels.duplication([tmp_path]) == 92.31


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


def test_lines_that_say_nothing_are_left_out_of_copied_code(tmp_path):
    noise = ["import org.x.A", "import org.x.B", "}", "})", "{", ")", "package x"]
    (tmp_path / "One.kt").write_text("\n".join([*noise, "fun one() = 1"]) + "\n")
    (tmp_path / "Two.kt").write_text("\n".join([*noise, "fun two() = 2"]) + "\n")
    assert levels.duplication([tmp_path]) == 0.0


def test_the_levels_carry_mutants_and_never_mutation():
    # "mutation" on the page means rules proven by a test; Pitest's mutants are another measure.
    assert "mutants" in levels.KEYS
    assert "mutation" not in levels.KEYS


def test_a_module_allowed_no_mutants_may_have_no_report(tmp_path):
    api = tmp_path / "api.xml"
    api.write_text("<mutations><mutation detected='true' status='KILLED'/></mutations>")
    missing = tmp_path / "missing.xml"
    assert levels.mutants([api, missing], may_be_missing={missing}) == {"killed": 1, "total": 1}


def test_any_other_missing_report_stops_the_measure(tmp_path):
    with pytest.raises(SystemExit, match="no Pitest report"):
        levels.mutants([tmp_path / "missing.xml"], may_be_missing=set())


def test_only_a_module_whose_classes_are_all_data_only_may_have_no_mutants():
    # tests/test_pitest_data_only.py checks each listed class; this checks the module has no other.
    for module in levels.NO_MUTANTS:
        declared = guard.declared_classes(levels.ROOT / module / "src" / "main")
        assert declared, module
        assert guard.unlisted_with_code(declared) == [], module


CORE = "org.claudevoice.mobile.core"


def _mutation(detected, status, file, package=CORE):
    owner = f"{package}.{file.removesuffix('.kt')}$inner"
    return (
        f"<mutation detected='{detected}' status='{status}'><sourceFile>{file}</sourceFile>"
        f"<mutatedClass>{owner}</mutatedClass></mutation>"
    )


def _report(path, *rows):
    path.write_text("<mutations>" + "".join(_mutation(*row) for row in rows) + "</mutations>")
    return path


def test_mutants_are_also_counted_per_module(tmp_path):
    core = _report(
        tmp_path / "core.xml",
        ("true", "KILLED", "Gate.kt"),
        ("true", "TIMED_OUT", "Gate.kt"),
        ("false", "SURVIVED", "Pairing.kt"),
        ("false", "NO_COVERAGE", "Tailnet.kt"),
    )
    api = _report(tmp_path / "api.xml", ("true", "KILLED", "Plugin.kt"))
    missing = tmp_path / "none.xml"
    assert levels.mutants_by_module(
        {"core": core, "plugin-api": api, "x": missing}, may_be_missing={missing}
    ) == {
        "core": {"killed": 2, "survived": 1, "no_coverage": 1, "total": 4},
        "plugin-api": {"killed": 1, "survived": 0, "no_coverage": 0, "total": 1},
    }


def test_survivors_are_counted_per_file_by_its_package_path(tmp_path):
    core = _report(
        tmp_path / "core.xml",
        ("true", "KILLED", "Gate.kt"),
        ("false", "SURVIVED", "Pairing.kt"),
        ("false", "NO_COVERAGE", "Pairing.kt"),
        ("false", "SURVIVED", "Tailnet.kt"),
    )
    assert levels.survivors({"core": core}, may_be_missing=set()) == {
        "core/org/claudevoice/mobile/core/Pairing.kt": 2,
        "core/org/claudevoice/mobile/core/Tailnet.kt": 1,
    }


def test_two_files_of_one_name_in_different_packages_keep_their_own_count(tmp_path):
    core = _report(
        tmp_path / "core.xml",
        ("false", "SURVIVED", "Gate.kt", "org.a"),
        ("false", "SURVIVED", "Gate.kt", "org.b"),
        ("false", "SURVIVED", "Gate.kt", "org.b"),
    )
    assert levels.survivors({"core": core}, may_be_missing=set()) == {
        "core/org/a/Gate.kt": 1,
        "core/org/b/Gate.kt": 2,
    }


def test_a_module_report_that_should_be_there_stops_the_per_module_measure(tmp_path):
    with pytest.raises(SystemExit):
        levels.mutants_by_module({"core": tmp_path / "missing.xml"}, may_be_missing=set())


def _module(root: pathlib.Path, path: str) -> None:
    (root / path).mkdir(parents=True, exist_ok=True)
    (root / path / "build.gradle.kts").write_text("")


def test_the_parts_measured_are_found_by_walking_the_plugins_folder(tmp_path):
    for path in ("plugins/status", "plugins/sms", "plugins/sms/rules", "plugins/notes"):
        _module(tmp_path, path)
    (tmp_path / "plugins" / "empty").mkdir()  # a folder with no build file is no plugin
    (tmp_path / "plugins" / "notes" / "rules").mkdir()  # nor are rules with no build file
    parts = levels.parts(tmp_path)
    assert parts.modules == (
        "core",
        "plugin-api",
        "app",
        "plugins/notes",
        "plugins/sms",
        "plugins/status",
        "plugins/sms/rules",
    )
    assert parts.mutated == ("core", "plugin-api", "plugins/sms/rules")
    assert parts.results == (
        ("core", "test"),
        ("plugin-api", "test"),
        ("app", "testDebugUnitTest"),
        ("plugins/notes", "testDebugUnitTest"),
        ("plugins/sms", "testDebugUnitTest"),
        ("plugins/status", "testDebugUnitTest"),
        ("plugins/sms/rules", "test"),
    )
    assert parts.test_tasks[-1] == ":plugins:sms:rules:test"
    assert parts.test_tasks[3] == ":plugins:notes:testDebugUnitTest"


def test_every_measure_builds_with_every_plugin_picked(monkeypatch):
    seen = []
    monkeypatch.setattr(levels.subprocess, "run", lambda args, **_: seen.append(args))
    levels.gradle("check")
    assert "-PcvPlugins=all" in seen[0], seen[0]


def test_a_capability_is_measured_and_tested_too_but_not_mutated(tmp_path):
    for path in ("plugins/sms_send", "capabilities/contacts"):
        _module(tmp_path, path)
    (tmp_path / "capabilities" / "empty").mkdir()  # a folder with no build file is no capability
    parts = levels.parts(tmp_path)
    assert parts.modules == ("core", "plugin-api", "app", "plugins/sms_send", "capabilities/contacts")
    assert "capabilities/contacts" not in parts.mutated  # an Android library, as a plugin is
    assert parts.results[-1] == ("capabilities/contacts", "testDebugUnitTest")
    assert parts.test_tasks[-1] == ":capabilities:contacts:testDebugUnitTest"


def test_a_tree_with_no_capabilities_folder_has_no_capability(tmp_path):
    _module(tmp_path, "plugins/status")
    assert levels.parts(tmp_path).modules == ("core", "plugin-api", "app", "plugins/status")
