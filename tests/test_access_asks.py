"""Reading what Android allows never asks, and every ask is written down first.

ADR-CVM-011 builds each plugin's ``access`` from a capability that only reads:
no file in it may name ``requestPermissions`` or a settings action, so no path
that builds ``access`` can ask the owner anything. And "never_asked" means this
install never asked, so every place in the app or a plugin that asks for a
permission writes the ask record first.
"""

import pathlib
import re

ROOT = pathlib.Path(__file__).parent.parent
CAPABILITY = ROOT / "capabilities/permissions/src/main"
ASKING = re.compile(r"requestPermissions\s*\(|ACTION_[A-Z_]*SETTINGS|Settings\.ACTION_")
RECORD = re.compile(r"\.record\s*\(")

# How many lines before an ask the record must be written: the same short block.
NEAR = 3


def _reader_problems(folder: pathlib.Path) -> list[str]:
    return [
        f"{path.relative_to(folder)}:{n}"
        for path in sorted(folder.rglob("*"))
        if path.is_file()
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1)
        if ASKING.search(line)
    ]


def _unrecorded_asks(text: str) -> list[int]:
    lines = text.splitlines()
    return [
        n + 1
        for n, line in enumerate(lines)
        if re.search(r"requestPermissions\s*\(", line)
        and not any(RECORD.search(before) for before in lines[max(0, n - NEAR) : n])
    ]


def _sources() -> list[pathlib.Path]:
    roots = [ROOT / "app/src/main", ROOT / "plugins", ROOT / "capabilities"]
    return sorted(p for r in roots for p in r.rglob("*.kt") if "/src/test/" not in p.as_posix())


def test_the_capability_that_reads_states_never_asks() -> None:
    assert CAPABILITY.is_dir()
    assert _reader_problems(CAPABILITY) == []


def test_the_reader_check_finds_an_ask_and_a_settings_page(tmp_path: pathlib.Path) -> None:
    (tmp_path / "A.kt").write_text("activity.requestPermissions(arrayOf(p), 1)\n", encoding="utf-8")
    (tmp_path / "B.kt").write_text("Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)\n", encoding="utf-8")
    assert _reader_problems(tmp_path) == ["A.kt:1", "B.kt:1"]


def test_every_ask_in_the_app_and_its_plugins_writes_the_record_first() -> None:
    texts = {p.relative_to(ROOT).as_posix(): p.read_text(encoding="utf-8") for p in _sources()}
    unrecorded = {path: _unrecorded_asks(text) for path, text in texts.items() if _unrecorded_asks(text)}
    assert unrecorded == {}
    assert any("requestPermissions" in text for text in texts.values()), "the check saw no ask"


def test_the_ask_check_refuses_an_ask_with_no_record_before_it() -> None:
    assert _unrecorded_asks("x()\nactivity.requestPermissions(a, 1)\n") == [2]
    assert _unrecorded_asks("asks.record(p)\nactivity.requestPermissions(a, 1)\n") == []
    assert _unrecorded_asks("activity.requestPermissions(a, 1)\nasks.record(p)\n") == [1]
