"""The shared cursor queue holds only pure logic (ADR-CVM-007).

`capabilities/cursorqueue` is a capability that a plugin's rules may use, so it must reach
nothing on the phone: plain Kotlin, no Android plugin, no manifest, no Android API and no
permission. quality-claude-voice asked for this pin in its review of #195.
"""

import pathlib

ROOT = pathlib.Path(__file__).parent.parent
QUEUE = ROOT / "capabilities/cursorqueue"


def reaches_android(module: pathlib.Path) -> list[str]:
    """Each way `module` could reach the phone: an Android plugin, a manifest, an API or a permission."""
    found = []
    build = (module / "build.gradle.kts").read_text()
    if "alias(libs.plugins.kotlin.jvm)" not in build:
        found.append("its build file does not apply the plain Kotlin plugin")
    if "libs.plugins.android" in build:
        found.append("its build file applies an Android plugin")
    found += [f"{p.relative_to(module)} is a manifest" for p in module.rglob("AndroidManifest.xml")]
    for source in sorted(module.rglob("*.kt")):
        text = source.read_text()
        if "import android." in text or "import androidx." in text:
            found.append(f"{source.relative_to(module)} uses an Android API")
        if "android.permission" in text:
            found.append(f"{source.relative_to(module)} names a permission")
    return found


def test_the_shared_cursor_queue_reaches_nothing_on_the_phone():
    assert reaches_android(QUEUE) == []


def test_the_check_notices_a_capability_that_reaches_android():
    found = reaches_android(ROOT / "capabilities/smsstore")
    assert "its build file does not apply the plain Kotlin plugin" in found
    assert "its build file applies an Android plugin" in found
    assert any(f.endswith("uses an Android API") for f in found), found
