"""A plugin's rules module stays plain Kotlin and mutated (ADR-CVM-007).

That rests on each rules module's own build file, so this reads every one in the tree. A
module that drops Pitest, or turns into an Android library, fails here.
"""

import pathlib

import pytest

ROOT = pathlib.Path(__file__).parent.parent
BUILDS = sorted(ROOT.glob("plugins/*/rules/build.gradle.kts"))


def plugins_applied(build: str) -> str:
    """The text of the build file's `plugins { }` block."""
    start = build.index("plugins {")
    return build[start : build.index("}", start)]


def test_the_walk_finds_the_rules_modules_in_the_tree():
    assert [b.parent.parent.name for b in BUILDS] == ["sms", "sms_send"]


@pytest.mark.parametrize("build", BUILDS, ids=lambda b: b.parent.parent.name)
def test_a_rules_module_applies_the_jvm_and_pitest_plugins_and_no_android_one(build):
    applied = plugins_applied(build.read_text())
    assert "libs.plugins.kotlin.jvm" in applied
    assert "libs.plugins.pitest" in applied
    assert "android" not in applied


def test_an_android_rules_module_would_fail():
    android = "plugins {\n    alias(libs.plugins.android.library)\n    alias(libs.plugins.pitest)\n}\n"
    assert "android" in plugins_applied(android)
