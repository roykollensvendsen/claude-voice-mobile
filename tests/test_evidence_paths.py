"""No evidence file holds a full local path.

The evidence under evidence/ is published with the repository, which is public. It holds test
output, and test output names files by their full local path, which names the machine's user.
So a path from a home or temporary folder must never be kept there: make it relative to the
repository first.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = re.compile(r"(?:file://)?/(?:home|Users|root|tmp)/")


def test_no_evidence_file_holds_a_local_path():
    found = [
        f"{path.relative_to(ROOT)}:{number}"
        for path in sorted((ROOT / "evidence").rglob("*"))
        if path.is_file()
        for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1)
        if LOCAL.search(line)
    ]
    assert found == [], f"local paths in evidence, make them relative: {found}"
