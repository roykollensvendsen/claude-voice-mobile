"""The shape of the phone's ``sms_since`` call is fixed in two JSON Schema files.

The laptop's ``sms-bridge`` is built in another repository, against these files
and a fake hub, so they are its contract. ADR-CVM-010 points to them, and the
numbers it gives in words have to be the numbers the files hold.

The project carries no JSON Schema library, so ``matches`` checks the part of
JSON Schema the two files use. ``test_the_checker_refuses_a_wrong_answer`` keeps
it from passing everything.
"""

import json
import pathlib
import re
from datetime import datetime

ROOT = pathlib.Path(__file__).parent.parent
SCHEMAS = ROOT / "docs/plugins/sms"
ADR = ROOT / "decisions/ADR-CVM-010-reading-and-sending-text-messages.md"
INPUT = "sms-since-input.json"
ANSWER = "sms-since-answer.json"
TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
    "integer": int,
    "null": type(None),
}


def schema(name: str) -> dict:
    return json.loads((SCHEMAS / name).read_text())


def resolve(node: dict, root: dict) -> dict:
    ref = node.get("$ref")
    if ref is None:
        return node
    assert ref.startswith("#/$defs/"), f"only local $defs are used, not {ref}"
    return root["$defs"][ref.removeprefix("#/$defs/")]


def type_errors(value: object, node: dict) -> list[str]:
    allowed = node["type"] if isinstance(node["type"], list) else [node["type"]]
    # A bool is an int in Python; JSON keeps them apart.
    ok = any(
        isinstance(value, TYPES[t]) and not (t == "integer" and isinstance(value, bool)) for t in allowed
    )
    return [] if ok else [f"{value!r} is not {allowed}"]


def scalar_errors(value: object, node: dict) -> list[str]:
    errors = []
    if "const" in node and value != node["const"]:
        errors.append(f"{value!r} is not {node['const']!r}")
    if "enum" in node and value not in node["enum"]:
        errors.append(f"{value!r} is not one of {node['enum']}")
    if isinstance(value, int) and "minimum" in node and value < node["minimum"]:
        errors.append(f"{value} is below {node['minimum']}")
    if isinstance(value, str):
        if len(value) < node.get("minLength", 0):
            errors.append(f"{value!r} is too short")
        if len(value) > node.get("maxLength", len(value)):
            errors.append(f"{len(value)} characters, more than {node['maxLength']}")
        if "pattern" in node and not re.search(node["pattern"], value):
            errors.append(f"{value!r} does not match {node['pattern']}")
        if node.get("format") == "date-time":
            when = datetime.fromisoformat(value)
            if when.utcoffset() is None:
                errors.append(f"{value!r} has no offset")
    return errors


def object_errors(value: dict, node: dict, root: dict) -> list[str]:
    errors = [f"missing {key}" for key in node.get("required", []) if key not in value]
    properties = node.get("properties", {})
    for key, item in value.items():
        if key in properties:
            errors += matches(item, properties[key], root)
        elif node.get("additionalProperties") is False:
            errors.append(f"{key} is not allowed")
    return errors


def matches(value: object, node: dict, root: dict) -> list[str]:
    """What is wrong with ``value`` under ``node``; empty when nothing is."""
    node = resolve(node, root)
    errors = type_errors(value, node) if "type" in node else []
    if errors:
        return errors
    errors = scalar_errors(value, node)
    if isinstance(value, dict):
        errors += object_errors(value, node, root)
    if isinstance(value, list):
        if len(value) > node.get("maxItems", len(value)):
            errors.append(f"{len(value)} items, more than {node['maxItems']}")
        for item in value:
            errors += matches(item, node["items"], root)
    return errors


def test_both_files_are_json_schema_with_examples():
    for name in (INPUT, ANSWER):
        doc = schema(name)
        assert doc["$schema"] == "https://json-schema.org/draft/2020-12/schema", name
        assert doc["$id"].endswith(f"docs/plugins/sms/{name}"), name
        assert doc["type"] == "object", name
        assert doc["additionalProperties"] is False, name
        assert doc["examples"], f"{name} has no examples"


def test_every_example_matches_its_schema():
    for name in (INPUT, ANSWER):
        doc = schema(name)
        for example in doc["examples"]:
            assert matches(example, doc, doc) == [], f"{name}: {example}"


def test_the_checker_refuses_a_wrong_answer():
    doc = schema(ANSWER)
    good = doc["examples"][0]
    message = good["messages"][0]
    wrong = [
        {k: v for k, v in good.items() if k != "cursor"},
        {**good, "extra": 1},
        {**good, "dropped": -1},
        {**good, "more": "no"},
        {**good, "messages": [{**message, "received_at": "2026-11-19T14:02:11"}]},
        {**good, "messages": [{**message, "from_kind": "contact"}]},
        {**good, "messages": [{**message, "untrusted": False}]},
        {**good, "messages": [{**message, "id": "141"}]},
        {**good, "messages": [{**message, "received_at": "2026-11-19T14:02:11Z"}]},
        {**good, "messages": [{**message, "text": "x" * 2001, "cut": True}]},
        {**good, "messages": [{k: v for k, v in message.items() if k != "cut"}]},
        {**good, "messages": [message] * (doc["properties"]["messages"]["maxItems"] + 1)},
    ]
    for answer in wrong:
        assert matches(answer, doc, doc), f"accepted {answer}"


def test_a_masked_message_names_only_its_sender():
    doc = schema(ANSWER)
    masked = [m for e in doc["examples"] for m in e["messages"] if m["masked"]]
    assert masked, "no example shows a masked message"
    for message in masked:
        assert message["text"] == f"a one-time code from {message['from']}"


def test_a_sender_the_network_did_not_give_is_an_unknown_sender():
    """Android can give no sender at all; the answer still names one, and masks as usual."""
    doc = schema(ANSWER)
    unknown = [m for e in doc["examples"] for m in e["messages"] if m["from"] == "an unknown sender"]
    assert unknown, "no example shows a message with no sender"
    assert all(m["from_kind"] == "name" for m in unknown)
    assert "an unknown sender" in doc["$defs"]["message"]["properties"]["from"]["description"]


def test_a_cursor_never_looks_like_a_message_id():
    """The bridge must not be tempted to build a cursor from the last id it saw."""
    examples = schema(ANSWER)["examples"]
    ids = [m["id"] for e in examples for m in e["messages"]]
    for answer in examples:
        assert not any(i in answer["cursor"] or answer["cursor"] in i for i in ids), answer["cursor"]


def test_an_answer_to_acknowledge_gives_a_cursor_of_its_own():
    """A lost answer's dropped count must not be cleared by the cursor before it.

    So every answer that carries a message or a dropped count has a cursor no other
    such answer gives. An empty answer with nothing dropped may give back the cursor
    it was passed, but a drop-only answer's cursor is given by no other example.
    """
    examples = schema(ANSWER)["examples"]
    carrying = [e for e in examples if e["messages"] or e["dropped"]]
    cursors = [e["cursor"] for e in carrying]
    assert len(set(cursors)) == len(cursors), cursors
    drop_only = [e for e in carrying if not e["messages"]]
    assert drop_only, "no drop-only example"
    for answer in drop_only:
        others = [e["cursor"] for e in examples if e is not answer]
        assert answer["cursor"] not in others, answer["cursor"]


def test_ids_after_a_reset_come_from_another_install():
    """After a reinstall the bridge keeps the ids it has seen, so a new install never reuses one."""
    examples = schema(ANSWER)["examples"]

    def installs(answers: list[dict]) -> set[str]:
        return {m["id"].split("-")[0] for e in answers for m in e["messages"]}

    after = installs([e for e in examples if e["reset"]])
    before = installs([e for e in examples if not e["reset"]])
    assert after, "no reset example holds a message"
    assert before, "no example before a reset holds a message"
    assert not after & before


def test_the_numbers_in_adr_cvm_010_are_the_numbers_in_the_files():
    # The ADR is wrapped, so a phrase may run over a line break.
    adr = " ".join(ADR.read_text().split())
    answer = schema(ANSWER)
    most = answer["properties"]["messages"]["maxItems"]
    assert f"at most {most} messages" in adr
    longest = answer["$defs"]["message"]["properties"]["text"]["maxLength"]
    assert f"first {longest} characters" in adr
    for name in (INPUT, ANSWER):
        assert f"docs/plugins/sms/{name}" in adr, f"ADR-CVM-010 does not point to {name}"
