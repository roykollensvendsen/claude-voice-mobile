"""ADR-CVM-010 says which dates and times a text may hold, though the digit rule refuses long runs.

The first version refuses any run of more than 6 digits, counted across spaces,
dots and dashes, so that a text cannot carry a code, a phone number or an
account number. That also refused "21.11.2026" and "14.30-16.00". The record now
names the exact date and time shapes that are taken out before the run is
counted, and the shapes that stay refused. The build (#159) pins each example.
"""

import pathlib

ROOT = pathlib.Path(__file__).parent.parent
ADR = ROOT / "decisions/ADR-CVM-010-reading-and-sending-text-messages.md"


def record() -> str:
    """The record joined into one line, since it is wrapped."""
    return " ".join(ADR.read_text().split())


def test_the_record_names_the_date_and_time_shapes_that_pass() -> None:
    text = record()
    for guard in (
        "**Dates and times in a text.**",
        "day.month.year",
        "year-month-day",
        "hours and minutes",
        "a day from 1 to 31, a month from 1 to 12",
        "hours from 0 to 23 and minutes from 0 to 59",
        "two times joined by a dash",
        "taken out before the run is counted",
    ):
        assert guard in text, guard


def test_the_record_keeps_numbers_written_with_dots_or_dashes_refused() -> None:
    text = record()
    for guard in (
        '"21.11.2026", "2026-11-21", "14.30-16.00" and "kl. 9:05" pass',
        '"22.33.44.55", "22-33-44-55", "1234.56.78901" and "21.11.2026.55" stay refused',
        "touches another digit, dot, slash or dash",
        "A dash never joins the parts of a date except in year-month-day",
    ):
        assert guard in text, guard
