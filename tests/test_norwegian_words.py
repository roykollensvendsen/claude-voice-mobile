"""The app's Norwegian words for joining the phone to the hub.

"Pare" and "paringskode" are borrowed from English "pair"; the app says "koble til" and
"koblingskode" instead.
"""

import pathlib
import re

STRINGS = pathlib.Path(__file__).parent.parent / "app/src/main/res/values-nb/strings.xml"


def test_the_pairing_code_is_a_koblingskode():
    text = STRINGS.read_text(encoding="utf-8")
    assert "Vis en koblingskode" in text
    assert "Koblingskode: %1$s" in text


def test_no_norwegian_string_says_pare():
    words = re.findall(
        r"\b(?:pare[a-zæøå]*|paring[a-zæøå]*)\b", STRINGS.read_text(encoding="utf-8"), re.IGNORECASE
    )
    assert words == []
