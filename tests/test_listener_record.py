"""ADR-CVM-003 says why the listener comes back by itself, and how the notice line decides.

The listener asks Android to create it again after the app is killed. The
record must say why, and that a recreated listener still starts nothing on a
phone that is not paired (#152).

The line shown when notifications are refused must never turn into a button
that does nothing. So whether Android showed the request is told by Android's
own flag read before and after, not by a guessed time or the screen pausing,
and a second refusal in a row opens the settings anyway (review of #134,
emulator run on #176).
"""

import pathlib

ROOT = pathlib.Path(__file__).parent.parent
ADR = ROOT / "decisions/ADR-CVM-003-the-apps-internal-design.md"
SERVICE = ROOT / "app/src/main/kotlin/org/claudevoice/mobile/app/ListenerService.kt"


def record() -> str:
    """The record joined into one line, since it is wrapped."""
    return " ".join(ADR.read_text().split())


def test_the_record_says_why_the_listener_is_created_again_and_what_it_still_refuses() -> None:
    text = record()
    for guard in (
        "**The listener comes back after Android kills the app.**",
        "`START_STICKY`",
        "whenever the owner takes a permission away",
        "with no intent",
        "stops at once when the phone is not paired",
        "A force stop is not undone",
    ):
        assert guard in text, guard


def test_the_listener_asks_to_be_created_again() -> None:
    assert "= START_STICKY" in SERVICE.read_text()


def test_whether_android_asked_is_told_by_the_flag_before_and_after_not_by_the_screen() -> None:
    text = record()
    for guard in (
        "reads `shouldShowRequestPermissionRationale` just before the request and again",
        "has asked before, by its own ask record",
        "false both before and after",
        "Android 15 starts its permission screen even when it refuses at once",
        "a turn of the phone",
        "If the owner closes that one too",
        "kept in the line's saved state",
        "On Android 8 to 12, `POST_NOTIFICATIONS` is not a permission the app can ask for",
        "a second refusal in a row from the line opens the settings",
        "never becomes a button that does nothing",
    ):
        assert guard in text, guard
    for gone in ("half a second", "the app's screen pauses", "loses the top place"):
        assert gone not in text, gone


def test_the_test_list_names_both_signals() -> None:
    text = record()
    assert "A refusal with the flag false before and after opens the notification settings" in text
    assert "a second refusal in a row opens them too" in text
