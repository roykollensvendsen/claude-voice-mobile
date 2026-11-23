"""A capability of pure logic reaches nothing on the phone (ADR-CVM-007).

A plugin's rules may use a capability whose build file applies no Android plugin, so every such
capability must be plain Kotlin with no manifest, no Android API, no permission, and no library
of its own beyond its tests. The set is the one `scripts/levels.py` mutates, by the rule the layer
check uses. quality-claude-voice asked for this pin in its review of #195, and quality-2 asked for
it to cover every pure capability and its dependencies in its review of #196, and to refuse files
and the network in its review of #197; the record (#197) adds processes and names this file, and
quality-claude-voice's re-stamp of it adds reflection and native code. voice-architect's review
of #198 adds reflection called by name, and the architect's n1 on #196 adds wildcard imports
and names written out in full.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import levels  # noqa: E402

# The only dependencies a pure capability may declare: those of its tests.
TEST_ONLY = re.compile(r"^\s*(testImplementation|testRuntimeOnly)\(")
# What reaches a file, a disk or the network, by import or written out in full in the code. A
# stream the caller hands over (java.io's Data and Byte streams, IOException) touches none, so
# java.io is not refused as a whole.
REACHES_OUT = re.compile(
    r"\b(java\.io\.(?:File\w*|RandomAccessFile)|java\.nio\.(?:file|channels)\.\w+|java\.net\.\w+"
    r"|kotlin\.io\.path\.\w+)"
)
# A whole package imported at once, from those that can reach beyond pure logic.
WILDCARD = re.compile(
    r"^import ((?:java\.(?:io|nio(?:\.\w+)?|net|lang\.reflect)|kotlin\.(?:io(?:\.path)?|reflect))\.\*)",
    re.MULTILINE,
)
# What reaches out by name, with no import: java.lang needs none, so the code itself is scanned.
BY_NAME = (
    ("starts a process", re.compile(r"\bProcessBuilder\b|\bRuntime\.getRuntime\(\)\.exec\b")),
    (
        "reaches by reflection",
        re.compile(
            r"\bjava\.lang\.reflect\b|\bClass\.forName\b|\bkotlin\.reflect\b|\bgetMethod\b"
            r"|\bgetDeclaredMethod\b|\bgetDeclaredField\b|\.invoke\(|\bClassLoader\b|\bloadClass\b"
        ),
    ),
    ("loads native code", re.compile(r"\bSystem\.load(?:Library)?\b")),
)
DEPENDENCIES = re.compile(r"^dependencies \{\n(.*?)^\}", re.DOTALL | re.MULTILINE)


def dependencies(build: str) -> list[str]:
    """The lines of the build file's dependencies block, comments and blank lines left out."""
    block = DEPENDENCIES.search(build)
    lines = block.group(1).splitlines() if block else []
    return [line.strip() for line in lines if line.strip() and not line.strip().startswith("//")]


def reaches_android(module: pathlib.Path) -> list[str]:
    """Each way `module` could reach beyond pure logic.

    That is an Android plugin, a manifest, an Android API, a permission, a library, a file,
    the network, a process, reflection or native code (ADR-CVM-007's meaning of pure logic).
    """
    found = []
    build = (module / "build.gradle.kts").read_text()
    if "alias(libs.plugins.kotlin.jvm)" not in build:
        found.append("its build file does not apply the plain Kotlin plugin")
    if "libs.plugins.android" in build:
        found.append("its build file applies an Android plugin")
    found.extend(
        f"its build file depends on more than its tests: {line}"
        for line in dependencies(build)
        if not TEST_ONLY.match(line)
    )
    found += [f"{p.relative_to(module)} is a manifest" for p in module.rglob("AndroidManifest.xml")]
    for source in sorted(module.rglob("*.kt")):
        text = source.read_text()
        if "import android." in text or "import androidx." in text:
            found.append(f"{source.relative_to(module)} uses an Android API")
        if "android.permission" in text:
            found.append(f"{source.relative_to(module)} names a permission")
        found.extend(
            f"{source.relative_to(module)} imports a whole package that reaches out: {name}"
            for name in WILDCARD.findall(text)
        )
        found.extend(
            f"{source.relative_to(module)} reaches files or the network: {name}"
            for name in REACHES_OUT.findall(text)
        )
        found.extend(
            f"{source.relative_to(module)} {what}: {name}"
            for what, pattern in BY_NAME
            for name in pattern.findall(text)
        )
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
    expected = "its build file depends on more than its tests: implementation(libs.okhttp)"
    assert reaches_android(tmp_path) == [expected]


def test_the_check_notices_a_capability_that_reaches_files_or_the_network(tmp_path):
    (tmp_path / "build.gradle.kts").write_text("plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n")
    source = tmp_path / "src/main/kotlin/Leak.kt"
    source.parent.mkdir(parents=True)
    source.write_text(
        "import java.io.DataOutputStream\n"
        "import java.io.File\n"
        "import java.io.FileOutputStream\n"
        "import java.io.RandomAccessFile\n"
        "import java.nio.file.Files\n"
        "import java.nio.channels.FileChannel\n"
        "import java.net.Socket\n"
    )
    found = reaches_android(tmp_path)
    reached = [f.split(": ", 1)[1] for f in found]
    assert reached == [
        "java.io.File",
        "java.io.FileOutputStream",
        "java.io.RandomAccessFile",
        "java.nio.file.Files",
        "java.nio.channels.FileChannel",
        "java.net.Socket",
    ], found
    assert all(f.startswith("src/main/kotlin/Leak.kt reaches files or the network: ") for f in found), found


def test_a_stream_the_caller_hands_over_is_no_file(tmp_path):
    (tmp_path / "build.gradle.kts").write_text("plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n")
    source = tmp_path / "Queue.kt"
    source.write_text(
        "import java.io.DataInputStream\nimport java.io.DataOutputStream\n"
        "import java.io.IOException\nimport java.io.ByteArrayOutputStream\n"
    )
    assert reaches_android(tmp_path) == []


def test_the_check_notices_a_capability_that_starts_a_process(tmp_path):
    (tmp_path / "build.gradle.kts").write_text("plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n")
    (tmp_path / "Run.kt").write_text(
        'fun a() = ProcessBuilder("ls").start()\nfun b() = Runtime.getRuntime().exec("ls")\n'
    )
    assert reaches_android(tmp_path) == [
        "Run.kt starts a process: ProcessBuilder",
        "Run.kt starts a process: Runtime.getRuntime().exec",
    ]


def test_the_check_notices_a_capability_that_reaches_out_by_name(tmp_path):
    (tmp_path / "build.gradle.kts").write_text("plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n")
    (tmp_path / "Name.kt").write_text(
        "import java.lang.reflect.Method\n"
        'fun a() = Class.forName("x")\n'
        'fun b() = System.loadLibrary("x")\n'
        'fun c() = System.load("/x.so")\n'
    )
    assert reaches_android(tmp_path) == [
        "Name.kt reaches by reflection: java.lang.reflect",
        "Name.kt reaches by reflection: Class.forName",
        "Name.kt loads native code: System.loadLibrary",
        "Name.kt loads native code: System.load",
    ]


def test_the_check_notices_reflection_called_by_name(tmp_path):
    (tmp_path / "build.gradle.kts").write_text("plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n")
    (tmp_path / "Call.kt").write_text(
        "import kotlin.reflect.KClass\n"
        'fun a(c: Class<*>) = c.getMethod("x").invoke(null)\n'
        'fun b(c: Class<*>) = c.getDeclaredMethod("x")\n'
        'fun c(c: Class<*>) = c.getDeclaredField("x")\n'
        'fun d(l: ClassLoader) = l.loadClass("x")\n'
    )
    reached = [f.split(": ", 1)[1] for f in reaches_android(tmp_path)]
    assert sorted(reached) == sorted(
        [
            "kotlin.reflect",
            "getMethod",
            ".invoke(",
            "getDeclaredMethod",
            "getDeclaredField",
            "ClassLoader",
            "loadClass",
        ]
    ), reached


def test_the_check_notices_a_wildcard_import_or_a_full_name_in_the_code(tmp_path):
    (tmp_path / "build.gradle.kts").write_text("plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n")
    (tmp_path / "Wide.kt").write_text(
        "import java.io.*\n"
        "import java.net.*\n"
        'fun a() = java.io.File("x")\n'
        'fun b() = java.nio.file.Paths.get("x")\n'
        'fun c() = java.net.URL("x")\n'
        'fun d() = kotlin.io.path.Path("x")\n'
    )
    reached = [f.split(": ", 1)[1] for f in reaches_android(tmp_path)]
    assert reached == [
        "java.io.*",
        "java.net.*",
        "java.io.File",
        "java.nio.file.Paths",
        "java.net.URL",
        "kotlin.io.path.Path",
    ], reached
