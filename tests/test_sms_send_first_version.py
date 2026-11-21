"""ADR-CVM-010 says what the first version of sending builds, and what waits.

The owner chose a smaller first version of ``sms_send`` (choice B, 21.11). The
record keeps the full design for later, so it must say plainly which parts the
first version holds, which wait, and what the first version does differently.
Otherwise the plugin is built and reviewed against parts no one built.
"""

import pathlib

ROOT = pathlib.Path(__file__).parent.parent
ADR = ROOT / "decisions/ADR-CVM-010-reading-and-sending-text-messages.md"


def first_version() -> str:
    """The paragraphs on the first version, joined, since the record is wrapped."""
    text = " ".join(ADR.read_text().split())
    start = text.index("**The first version of sending.**")
    end = text.index("**`sms_send_request(to, text)`**", start)
    return text[start:end]


def test_the_record_says_what_the_first_version_of_sending_holds() -> None:
    part = first_version()
    for kept in (
        "the hub denies it in every role",
        "10 sends an hour, 30 in 24 hours, and 3 to one number in 10 minutes",
        "at most 1000 characters, with no link and no run of more than 6 digits",
        "`rate_limited`",
        "`text_refused`",
        "a restart of the app starts the counts again",
    ):
        assert kept in part, kept


def test_the_record_says_what_waits_for_the_full_version() -> None:
    part = first_version()
    for parked in (
        "the sent-before check",
        "the mobile ranges",
        "a draft lapsing after 15 minutes",
        "one draft per recipient",
        "the crash marker",
        "`sms_send_outcomes`",
        "`READ_SMS`",
    ):
        assert parked in part, parked
