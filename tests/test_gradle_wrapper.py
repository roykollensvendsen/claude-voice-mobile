"""Every Gradle wrapper in the repository is Gradle 8.11.1's own, byte for byte.

The scripts are what ``gradle wrapper --gradle-version 8.11.1`` writes, and the jar is the one
Gradle publishes for that version. A look-alike script once stood in for the real one; pinning
each file's checksum makes any change to a wrapper a change someone has to make on purpose.
"""

import hashlib
import pathlib

import pytest

ROOT = pathlib.Path(__file__).parent.parent
PINNED = {
    "gradlew": "a3648413b47ef77af21d5ebc36c687c7d103aaef3e17f33de7d4f080a6f300a3",
    "gradlew.bat": "57931b17dd228e5c24dac90e815d0bf82477e831a4618dfab4136f5446b42a9f",
    "gradle/wrapper/gradle-wrapper.jar": "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046",
}
SKIP = ("build", ".gradle", ".venv", "node_modules")


def wrapper_roots() -> list[pathlib.Path]:
    """Every folder with a Gradle wrapper, found by walking rather than listed by hand."""
    found = {p.parent.parent.parent for p in ROOT.rglob("gradle/wrapper/gradle-wrapper.properties")}
    return sorted(r for r in found if not any(part in SKIP for part in r.relative_to(ROOT).parts))


def test_the_walk_finds_the_repositorys_wrapper_and_the_prototypes():
    names = {r.relative_to(ROOT).as_posix() for r in wrapper_roots()}
    assert {".", "prototype/audio-focus"} <= names


@pytest.mark.parametrize("root", wrapper_roots(), ids=lambda r: r.relative_to(ROOT).as_posix() or ".")
def test_each_wrapper_file_is_gradles_own(root):
    for name, digest in PINNED.items():
        path = root / name
        assert path.is_file(), f"{path.relative_to(ROOT)} is missing"
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, (
            f"{path.relative_to(ROOT)} is not Gradle's"
        )
