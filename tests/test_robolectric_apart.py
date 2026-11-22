"""The measured test time leaves Robolectric's tests out, and no test run has a time cap.

Robolectric starts an Android image before its first test. On CI (#169, run 45022042624) the
app's test task took 13 to 21 s with it, and the timed run went to 51.5 s against a budget of
30, a cost that says nothing about the tests themselves. So the timed run in scripts/levels.py
leaves every Robolectric test out. No test run gets a time cap in its place: the owner's rule.
"""

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "scripts"))

import levels

ROOT = pathlib.Path(__file__).parent.parent
APP_TESTS = ROOT / "app/src/test/kotlin"
BUILD = ROOT / "app/build.gradle.kts"
PACKAGE = "org.claudevoice.mobile.app.robolectric"


def test_the_timed_run_leaves_the_robolectric_tests_out():
    found = levels.parts(ROOT)
    timed = levels.timed_tests(found)
    assert levels.LEAVE_OUT_ROBOLECTRIC in timed, timed
    for task in found.test_tasks:
        assert task in timed, task


def test_every_robolectric_test_is_in_the_package_the_timed_run_leaves_out():
    runs_robolectric = [
        path.relative_to(APP_TESTS).as_posix()
        for path in sorted(APP_TESTS.rglob("*.kt"))
        if "RobolectricTestRunner" in path.read_text(encoding="utf-8")
    ]
    assert runs_robolectric, "no Robolectric test found"
    folder = PACKAGE.replace(".", "/") + "/"
    assert [p for p in runs_robolectric if not p.startswith(folder)] == []


def test_the_app_build_leaves_that_package_out_when_asked_and_caps_no_test_run():
    build = BUILD.read_text(encoding="utf-8")
    flag = levels.LEAVE_OUT_ROBOLECTRIC.removeprefix("-P")
    assert f'gradleProperty("{flag}")' in build
    assert f'excludeTestsMatching("{PACKAGE}.*")' in build
    assert not re.search(r"\btimeout\s*\.", build)
