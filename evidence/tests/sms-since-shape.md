# sms_since: the shape the laptop's bridge is built against

The red run, with the test written before either schema file existed, is
`red-sms-since-shape.txt`: 5 tests failed.

- **Two JSON Schema files** under `docs/plugins/sms/`: the tool's input and its answer. Each is
  draft 2020-12, refuses fields it does not name, and carries examples
  (`test_both_files_are_json_schema_with_examples`).
- **Every example fits its schema.** The project has no JSON Schema library, so the test checks
  the part of JSON Schema the files use: types, required and unknown fields, `const`, `enum`,
  `minimum`, `minLength`, `maxItems`, and a time that must carry its offset.
- **The checker is not empty.** In round 1, eight wrong answers are each refused: a missing cursor, an
  unknown field, a negative dropped count, a word for a boolean, a time without an offset, an
  unknown kind of sender, `untrusted: false`, and 51 messages in one answer.
- **A masked message names only its sender**, as "a one-time code from <from>".
- **ADR-CVM-010 and the files agree**: the ADR points to both files, and its "at most 50
  messages" is the answer's `maxItems`.

The phone's own tool is not built yet. When it is, its tests check each answer against
`sms-since-answer.json`, as ADR-CVM-010's list of tests says.

## Round 2, after review

The red run is appended to `red-sms-since-shape.txt`: 4 of the 8 tests failed against round 1's
files.

- **A lost answer cannot lose its dropped count** (voice-architect's B1). The cursor is opaque
  and never looks like a message id (`test_a_cursor_never_looks_like_a_message_id`). Every
  answer carrying a message or a dropped count gives a cursor of its own, and the examples hold a
  drop-only answer whose cursor no other example gives
  (`test_an_answer_to_acknowledge_gives_a_cursor_of_its_own`).
- **Ids never repeat across installs** (quality's b1). An id is `<install>-<number>`, by a
  pattern, and the reset example's ids share no install part with the others
  (`test_ids_after_a_reset_come_from_another_install`).
- **The offset is held by a pattern**, since 2020-12 treats `format` as a note. A time ending in
  `Z` is refused.
- **A text is at most 2000 characters**, with `cut` saying so, and ADR-CVM-010's "first 2000
  characters" is the schema's `maxLength`.
- The checker now refuses twelve wrong answers.
