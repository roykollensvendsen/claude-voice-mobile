"""A capability of pure logic reaches nothing on the phone (ADR-CVM-007).

A plugin's rules may use a capability whose build file applies no Android plugin, so every such
capability must be plain Kotlin with no manifest, no Android API, no permission, and no library
of its own beyond its tests. The set is the one `scripts/levels.py` mutates, by the rule the layer
check uses. quality-claude-voice asked for this pin in its review of #195, and quality-2 asked for
it to cover every pure capability and its dependencies in its review of #196.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import levels  # noqa: E402

# The only dependencies a pure capability may declare: those of its tests.
TEST_ONLY = re.compile(r"^\s*(testImplementation|testRuntimeOnly)\(")
DEPENDENCIES = re.compile(r"^dependencies \{\n(.*?)^\}", re.DOTALL | re.MULTILINE)


def dependencies(build: str) -> list[str]:
    """The lines of the build file's dependencies block, comments and blank lines left out."""
    block = DEPENDENCIES.search(build)
    lines = block.group(1).splitlines() if block else []
    return [line.strip() for line in lines if line.strip() and not line.strip().startswith("//")]


def reaches_android(module: pathlib.Path) -> list[str]:
    """Each way `module` could reach beyond pure logic: a plugin, a manifest, an API, a permission, a library."""
    found = []
    build = (module / "build.gradle.kts").read_text()
    if "alias(libs.plugins.kotlin.jvm)" not in build:
        found.append("its build file does not apply the plain Kotlin plugin")
    if "libs.plugins.android" in build:
        found.append("its build file applies an Android plugin")
    for line in dependencies(build):
        if not TEST_ONLY.match(line):
            found.append(f"its build file depends on more than its tests: {line}")
    found += [f"{p.relative_to(module)} is a manifest" for p in module.rglob("AndroidManifest.xml")]
    for source in sorted(module.rglob("*.kt")):
        text = source.read_text()
        if "import android." in text or "import androidx." in text:
            found.append(f"{source.relative_to(module)} uses an Android API")
        if "android.permission" in text:
            found.append(f"{source.relative_to(module)} names a permission")
    return found


def test_the_shared_cursor_queue_is_a_capability_of_pure_logic():
    assert "capabilities/cursorqueue" in levels.pure_capabilities(ROOT)


def test_every_capability_of_pure_logic_reaches_nothing_on_the_phone():
    for capability in levels.pure_capabilities(ROOT):
        assert reaches_android(ROOT / capability) == [], capability


def test_the_check_notices_a_capability_that_reaches_android():
    found = reaches_android(ROOT / "capabilities/smsstore")
    assert "its build file does not apply the plain Kotlin plugin" in found
    assert "its build file applies an Android plugin" in found
    assert any(f.endswith("uses an Android API") for f in found), found


def test_the_check_notices_a_library_beyond_the_tests(tmp_path):
    (tmp_path / "build.gradle.kts").write_text(
        "plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n"
        "dependencies {\n    implementation(libs.okhttp)\n    testImplementation(libs.junit.jupiter)\n}\n"
    )
    assert reaches_android(tmp_path) == ["its build file depends on more than its tests: implementation(libs.okhttp)"]
