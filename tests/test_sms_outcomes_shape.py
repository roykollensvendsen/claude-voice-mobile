"""The shape of the phone's ``sms_send_outcomes`` call is fixed in two JSON Schema files.

ADR-CVM-010 gives it in words only: the laptop's poll for how each held text
ended, one item per recipient by position in the read-back, with the same
cursor, acknowledgement and resend as ``sms_since``. The laptop's bridge and the
Android build (#154) both build against these files, so they are the contract.
No item ever holds a number, a name or a text.
"""

import json
import pathlib

from test_sms_since_shape import matches

ROOT = pathlib.Path(__file__).parent.parent
SCHEMAS = ROOT / "docs/plugins/sms"
ADR = ROOT / "decisions/ADR-CVM-010-reading-and-sending-text-messages.md"
INPUT = "sms-send-outcomes-input.json"
ANSWER = "sms-send-outcomes-answer.json"
OUTCOMES = ["sent", "delivered", "stopped", "failed", "unknown"]


def schema(name: str) -> dict:
    return json.loads((SCHEMAS / name).read_text())


def test_both_files_are_json_schema_with_examples() -> None:
    for name in (INPUT, ANSWER):
        doc = schema(name)
        assert doc["$schema"] == "https://json-schema.org/draft/2020-12/schema", name
        assert doc["$id"].endswith(f"docs/plugins/sms/{name}"), name
        assert doc["additionalProperties"] is False, name
        assert doc["examples"], name


def test_every_example_matches_its_schema() -> None:
    for name in (INPUT, ANSWER):
        doc = schema(name)
        for example in doc["examples"]:
            assert matches(example, doc, doc) == [], (name, example)


def test_an_outcome_is_one_of_five_and_names_its_recipient_by_position_only() -> None:
    doc = schema(ANSWER)
    item = doc["$defs"]["outcome"]
    assert item["properties"]["outcome"]["enum"] == OUTCOMES
    assert item["properties"]["recipient"]["enum"] == [1, 2, 3, 4, 5]
    assert item["additionalProperties"] is False
    assert set(item["properties"]) == {"id", "token", "recipient", "outcome", "at", "reason"}


def test_the_checker_refuses_an_outcome_carrying_a_number_or_an_unknown_value() -> None:
    doc = schema(ANSWER)
    good = doc["examples"][0]
    item = good["outcomes"][0]
    for bad in (
        {**item, "number": "+4700000021"},
        {**item, "outcome": "probably_sent"},
        {**item, "recipient": 6},
        {**item, "at": "2026-11-22T14:02:11"},
        {**item, "reason": "failed: +4700000021 unreachable"},
    ):
        assert matches({**good, "outcomes": [bad]}, doc, doc) != [], bad


REASONS = {
    "sent": ["handed to the network"],
    "delivered": ["the network reported it delivered"],
    "stopped": ["stopped by the owner", "the app restarted while it was held"],
    "failed": [
        "no service",
        "radio off",
        "no default subscription",
        "refused by Android",
        "the network reported it failed",
    ],
    "unknown": ["it may have been sent; check the text app"],
}


def record() -> str:
    return " ".join(ADR.read_text().split())


def test_a_reason_is_a_fixed_phrase_listed_beside_its_outcome() -> None:
    reason = schema(ANSWER)["$defs"]["outcome"]["properties"]["reason"]
    assert sorted(reason["enum"]) == sorted(p for ps in REASONS.values() for p in ps)
    text = record()
    for outcome, phrases in REASONS.items():
        line = f"- `{outcome}`: " + ", ".join(f'"{p}"' for p in phrases) + "."
        assert line in text, line


def test_the_record_pins_what_follows_an_outcome() -> None:
    text = record()
    for guard in (
        "The voice reports the latest item for each recipient",
        "a text that was sent and then failed is never told as sent",
        "The token an outcome echoes is already spent, and can never confirm again.",
        "An `unknown` text is never sent again.",
        "never free text",
    ):
        assert guard in text, guard


def test_the_record_points_to_the_files() -> None:
    text = ADR.read_text()
    assert INPUT in text
    assert ANSWER in text


def test_a_file_that_cannot_be_read_counts_a_drop_and_is_never_silent() -> None:
    text = " ".join(record().split())
    assert "A marker file or an outcome file that can't be read counts at least one dropped outcome" in text
    assert "A missing one counts none" in text
    assert "The hub hands the voice only a tool's description" in text
    assert "`sms_send_outcomes`' description carries this sentence word for word" in text
    assert TOOL_WORDS in text
    dropped = schema(ANSWER)["properties"]["dropped"]["description"]
    assert "a marker or outcome file that could not be read counts at least one" in dropped


TOOL_WORDS = (
    "When dropped is above 0, say that some outcomes were lost and that a text may have been "
    "sent; check the text app."
)
