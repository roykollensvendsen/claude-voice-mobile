"""ADR-CVM-010 answers what the builds of several recipients, near names and dates asked.

Each answer came from a review of a build or of the record: which held draft a
new list joins (#174), where the read-back marks apply and how far back they
look (#188), what "one letter off" means and how lookups are counted (#177),
and what a bracket next to a date does (#187). The record says each in words,
so the next build reads it there rather than in a review thread.
"""

import pathlib

ROOT = pathlib.Path(__file__).parent.parent
ADR = ROOT / "decisions/ADR-CVM-010-reading-and-sending-text-messages.md"


def record() -> str:
    """The record joined into one line, since it is wrapped."""
    return " ".join(ADR.read_text().split())


def test_the_record_says_which_held_draft_a_new_list_joins() -> None:
    text = record()
    for guard in (
        "the oldest of them takes the new list and text as its next version, with the same token",
        "Every other held draft that shares a recipient with it is stopped",
        "two yeses can never send one person two texts",
        "says how many held drafts it replaced",
    ):
        assert guard in text, guard


def test_the_record_says_where_the_read_back_marks_apply_and_how_far_back() -> None:
    text = record()
    for guard in (
        "The marks apply to a list of one too",
        "every sent text Android still holds, with no time window",
        "without that there is no mark and no claim",
        "before a build that sends to several reaches the owner's phone",
    ):
        assert guard in text, guard


def test_the_record_defines_one_letter_off_and_counts_a_request_once() -> None:
    text = record()
    for guard in (
        "an edit distance of 1 after folding",
        "two neighbouring letters swapped",
        "one request counts as one try, however many of the steps above it walks",
        "except the choices, each a name and the last 2 digits, and the chosen draft's read-back",
    ):
        assert guard in text, guard


def test_the_record_says_a_bracket_glued_to_a_date_keeps_it_in() -> None:
    text = record()
    for guard in (
        "The word on each side is the whole run of characters between spaces",
        '"(21.11.2026)" stays refused',
    ):
        assert guard in text, guard
