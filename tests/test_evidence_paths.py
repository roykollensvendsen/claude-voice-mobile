"""No evidence file holds a full local path.

The evidence under evidence/ is published with the repository, which is public. It holds test
output, and test output names files by their full local path, which names the machine's user.
So a path from a home or temporary folder must never be kept there: make it relative to the
repository first.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = re.compile(
    # Not inside a web address or a longer path: only a start, space, quote, bracket, =, :, comma
    # or file:// before it. In a web address a letter of the host name comes before the slash.
    # Linux and macOS:
    r"(?:^|(?<=[\s'\"(\[=:,])|file://)/(?:home|Users|root|tmp|private/var/folders|var/folders)/"
    r"|[A-Za-z]:[\\/](?:Users|Windows[\\/]Temp)[\\/]",  # Windows, either slash
    re.IGNORECASE,
)


def test_no_evidence_file_holds_a_local_path():
    found = [
        f"{path.relative_to(ROOT)}:{number}"
        for path in sorted((ROOT / "evidence").rglob("*"))
        if path.is_file()
        for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1)
        if LOCAL.search(line)
    ]
    assert found == [], f"local paths in evidence, make them relative: {found}"


def test_the_pattern_knows_each_system_s_home_and_temporary_folders():
    for path in (
        "/home/a/x.kt",
        "file:///tmp/x",
        "/Users/a/x",
        "/private/var/folders/ab/T/x",
        "/var/folders/ab/T/x",
        r"C:\Users\a\x.kt",
        "C:/Users/a/x.kt",
        r"c:\Windows\Temp\x",
        "[/home/a/x.kt]",
        "at x:/tmp/y",
        "a,/Users/b/x",
    ):
        assert LOCAL.search(path), path
    for path in ("core/src/main/kotlin/x.kt", "evidence/tests/a.txt", "https://example.com/home/"):
        assert not LOCAL.search(path), path
