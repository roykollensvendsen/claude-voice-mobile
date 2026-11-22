"""ADR-CVM-011 says how the phone reports whether each thing a plugin uses is allowed now.

The owner turned contacts off and the hub could not tell. The next protocol
version gives each plugin entry an `access` object (claude-voice #442). The
phone's record must hold the guards it was reviewed under: plain states only,
never a question to the owner, read fresh on every answer, and granted only
when every permission behind a use is.
"""

import pathlib

ROOT = pathlib.Path(__file__).parent.parent
ADR = ROOT / "decisions/ADR-CVM-011-saying-what-the-phone-allows-now.md"
INDEX = ROOT / "decisions/README.md"


def record() -> str:
    """The record joined into one line, since it is wrapped."""
    return " ".join(ADR.read_text().split())


def test_the_record_is_listed() -> None:
    assert "ADR-CVM-011-saying-what-the-phone-allows-now.md" in INDEX.read_text()


def test_reporting_never_asks_and_carries_plain_states_only() -> None:
    text = record()
    for guard in (
        '"granted", "refused" or "never_asked"',
        "`checkSelfPermission`",
        "never calls `requestPermissions`",
        "never anything read with the access",
        "never logged",
    ):
        assert guard in text, guard


def test_a_use_is_granted_only_when_all_its_permissions_are() -> None:
    text = record()
    assert "granted only when every Android permission behind it is" in text
    assert "a pure rule" in text


def test_access_never_widens_uses_and_is_read_fresh() -> None:
    text = record()
    for guard in (
        "one key per name in `uses`, and no other",
        "A refusal changes `access`, never `uses`",
        "read fresh in every `agent_info` answer, never cached",
    ):
        assert guard in text, guard


def test_the_record_says_what_gets_worse() -> None:
    text = record()
    assert "A permission given back shows only at the hub's next read" in text


def test_both_missing_values_are_fixed_in_the_settings_since_the_app_asks_for_none() -> None:
    """Both missing values get the settings fix, since the app asks for no plugin's permission.

    Review of #164: "open the app once" fixed nothing. An install from before the record lands on
    never_asked too.
    """
    text = record()
    for guard in (
        "Today the app asks for no plugin's permission",
        'Both "refused" and "never_asked" are fixed the same way',
        "in the phone's settings",
        "an install from before the record",
        "refused or never_asked",
        "no file in the capability names `requestPermissions`",
    ):
        assert guard in text, guard
    assert "open the app once" not in text
