r"""The phone's code never uses a regex flag that Android's engine refuses.

On the JVM, ``(?U)`` makes ``\s`` and its kin match all of Unicode. Android compiles
patterns with ICU, which refuses that flag, so a pattern using it passes every test
off the phone and then throws the first time the phone loads the class. That is how
the first run of the text-message receiver on a phone crashed and lost its message.
"""

import pathlib
import re

ROOT = pathlib.Path(__file__).parent.parent
REFUSED = re.compile(r"\(\?[a-zA-Z]*U[a-zA-Z]*[):]|UNICODE_CHARACTER_CLASS")


def _main_sources() -> list[pathlib.Path]:
    return sorted(p for p in ROOT.glob("**/src/main/**/*.kt") if "/build/" not in p.as_posix())


def test_no_code_on_the_phone_uses_a_flag_android_refuses() -> None:
    found = [
        f"{p.relative_to(ROOT)}:{n}"
        for p in _main_sources()
        for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1)
        if REFUSED.search(line)
    ]
    assert found == []


def test_the_check_finds_the_flag_in_its_forms() -> None:
    for line in ['Regex("(?U)\\\\s+")', 'Regex("(?iU)x")', "Pattern.UNICODE_CHARACTER_CLASS"]:
        assert REFUSED.search(line), line
    assert not REFUSED.search('Regex("(?i)x")')
