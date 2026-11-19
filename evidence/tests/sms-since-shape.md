# sms_since: the shape the laptop's bridge is built against

The red run, with the test written before either schema file existed, is
`red-sms-since-shape.txt`: 5 tests failed.

- **Two JSON Schema files** under `docs/plugins/sms/`: the tool's input and its answer. Each is
  draft 2020-12, refuses fields it does not name, and carries examples
  (`test_both_files_are_json_schema_with_examples`).
- **Every example fits its schema.** The project has no JSON Schema library, so the test checks
  the part of JSON Schema the files use: types, required and unknown fields, `const`, `enum`,
  `minimum`, `minLength`, `maxItems`, and a time that must carry its offset.
- **The checker is not empty.** Eight wrong answers are each refused: a missing cursor, an
  unknown field, a negative dropped count, a word for a boolean, a time without an offset, an
  unknown kind of sender, `untrusted: false`, and 51 messages in one answer.
- **A masked message names only its sender**, as "a one-time code from <from>".
- **ADR-CVM-010 and the files agree**: the ADR points to both files, and its "at most 50
  messages" is the answer's `maxItems`.

The phone's own tool is not built yet. When it is, its tests check each answer against
`sms-since-answer.json`, as ADR-CVM-010's list of tests says.
