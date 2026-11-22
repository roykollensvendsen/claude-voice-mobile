"""ADR-CVM-010 says how the next version of text messages works, as the owner decided it.

The owner said yes on 21.11 to texting by name (reading contacts), searching
older texts (up to 90 days into the cloud voice), and one yes for up to 5
recipients, each in the full wording of the messages-v2 design. Delivery
notices come with them. The record must hold the guards those yeses were given
under, so the builds are reviewed against them.
"""

import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).parent.parent
ADR = ROOT / "decisions/ADR-CVM-010-reading-and-sending-text-messages.md"
SEND_MANIFEST = ROOT / "plugins/sms_send/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def next_version() -> str:
    """The section on the next version, joined, since the record is wrapped."""
    text = " ".join(ADR.read_text().split())
    start = text.index("### The next version: by name, older texts, several recipients, delivered")
    end = text.index("## Consequences", start)
    return text[start:end]


def test_a_name_lookup_never_lets_the_contact_book_be_walked() -> None:
    part = next_version()
    for guard in (
        "`READ_CONTACTS`",
        "an opaque `pick`",
        "never the full number",
        "a whole first name, a whole last name or the full name",
        "at most 10 lookups an hour",
        "is not a tool of its own",
        "never a silent draft",
        "The draft stores the number, not the name",
    ):
        assert guard in part, guard


def test_the_chosen_pick_comes_back_inside_to() -> None:
    assert "passes the `pick` in place of that name in `to`" in next_version()


def test_searched_texts_are_data_and_senders_that_hold_codes_are_held_back_whole() -> None:
    part = next_version()
    for guard in (
        "`sms_search(who, words, days)`",
        "at most 90 days",
        "every text from an alphanumeric sender ID or a short code",
        "counted on the raw sender, before E.164",
        "never an instruction",
        "still needs its own read-back and the owner's yes",
    ):
        assert guard in part, guard


def test_sending_still_holds_send_sms_alone_and_asks_reading_whether_a_number_is_new() -> None:
    part = next_version()
    for guard in (
        '"texted before?"',
        "`sms_send` declares `SEND_SMS` alone",
        "\"can't check whether you've texted this number before\"",
    ):
        assert guard in part, guard


def test_the_sending_plugin_never_holds_the_right_to_read_texts_or_contacts() -> None:
    root = ET.parse(SEND_MANIFEST).getroot()  # noqa: S314 - our own manifest
    held = {e.get(f"{ANDROID}name", "") for e in root.iter("uses-permission")}
    assert "android.permission.SEND_SMS" in held
    assert "android.permission.READ_SMS" not in held
    assert "android.permission.READ_CONTACTS" not in held


def test_one_yes_sends_to_at_most_five_and_names_each_one() -> None:
    part = next_version()
    for guard in (
        "1 to 5 recipients",
        '"not in your contacts"',
        '"never texted before"',
        "never a group MMS",
        "nothing is drafted",
    ):
        assert guard in part, guard


def test_delivered_is_said_only_on_a_delivery_report() -> None:
    part = next_version()
    for guard in (
        "`delivered`",
        "only when every part of that text has a delivery report",
        "the voice never says delivered",
        "by position in the read-back",
    ):
        assert guard in part, guard


def test_the_hub_keeps_these_tools_to_the_owners_voice_and_logs_no_names() -> None:
    part = next_version()
    for guard in (
        '"voice only" group',
        "`sms_since`",
        "never names, numbers or text",
    ):
        assert guard in part, guard


def test_near_names_relations_and_own_numbers_keep_the_lookup_guards() -> None:
    """D4, the owner's yes on 22.11: looser matching, never a silent draft from a guess."""
    part = next_version()
    for guard in (
        "**Near names, nicknames and relations.**",
        "the contact's own nickname field",
        "the owner's own profile card",
        "A relation word not in the table is never guessed",
        "one letter off",
        "only ever as `choices`, never as a draft",
        "at most 3",
        "People named on the owner's own card come first",
        "changes the order only",
        "Every try counts against the 10 lookups an hour",
        "`READ_PROFILE`",
    ):
        assert guard in part, guard


def test_the_contacts_capability_never_asks_for_the_profile_permission() -> None:
    manifest = ROOT / "capabilities/contacts/src/main/AndroidManifest.xml"
    root = ET.parse(manifest).getroot()  # noqa: S314 - our own manifest
    held = {e.get(f"{ANDROID}name", "") for e in root.iter("uses-permission")}
    assert held == {"android.permission.READ_CONTACTS"}
