"""Every file the build downloads is checked against a known checksum (#170).

Gradle checks each dependency against ``gradle/verification-metadata.xml`` and fails the
build on a file it has no sha256 for, or one whose sha256 differs. Robolectric would fetch
its Android images by itself, outside Gradle and so unchecked; instead it runs offline, and
each image it needs is a Gradle dependency like any other, so the same check covers it.
"""

import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).parent.parent
CATALOG = ROOT / "gradle/libs.versions.toml"
METADATA = ROOT / "gradle/verification-metadata.xml"
BUILD = ROOT / "app/build.gradle.kts"
TESTS = ROOT / "app/src/test/kotlin"
NS = {"g": "https://schema.gradle.org/dependency-verification"}
IMAGE = re.compile(
    r'^robolectric-sdk(\d+) = \{ module = "org\.robolectric:android-all-instrumented", '
    r'version = "([^"]+)" \}$',
    re.MULTILINE,
)
CONFIG = re.compile(r"@Config\(sdk = \[(\d+)\]\)")


def _robolectric_tests() -> list[pathlib.Path]:
    return [
        p for p in sorted(TESTS.rglob("*.kt")) if "RobolectricTestRunner" in p.read_text(encoding="utf-8")
    ]


def _images() -> dict[int, str]:
    return {int(sdk): version for sdk, version in IMAGE.findall(CATALOG.read_text(encoding="utf-8"))}


def test_gradle_verifies_every_dependency_by_its_sha256():
    root = ET.parse(METADATA).getroot()  # noqa: S314 - our own file
    assert root.findtext("g:configuration/g:verify-metadata", namespaces=NS) == "true"
    assert root.findall("g:components/g:component", NS), "no component has a checksum"


def test_every_robolectric_test_class_names_its_android_version():
    tests = _robolectric_tests()
    assert tests, "no Robolectric test found"
    missing = [
        p.name
        for p in tests
        if not re.search(r"@Config\(sdk = \[\d+\]\)\s*\n(?:@\w+.*\n)*class ", p.read_text(encoding="utf-8"))
    ]
    assert missing == []


def test_each_android_version_a_test_runs_on_is_a_pinned_gradle_dependency():
    used = {int(n) for p in _robolectric_tests() for n in CONFIG.findall(p.read_text(encoding="utf-8"))}
    assert used, "no @Config found"
    assert sorted(used - _images().keys()) == []


def test_robolectric_runs_offline_from_the_images_gradle_fetched():
    build = BUILD.read_text(encoding="utf-8")
    assert 'systemProperty("robolectric.offline", "true")' in build
    assert 'systemProperty("robolectric.dependency.dir"' in build


def test_each_pinned_image_has_a_checksum():
    root = ET.parse(METADATA).getroot()  # noqa: S314 - our own file
    checked = {
        c.get("version")
        for c in root.iterfind("g:components/g:component", NS)
        if c.get("group") == "org.robolectric"
        and c.get("name") == "android-all-instrumented"
        and c.find("g:artifact/g:sha256", NS) is not None
    }
    assert sorted(set(_images().values()) - checked) == []
