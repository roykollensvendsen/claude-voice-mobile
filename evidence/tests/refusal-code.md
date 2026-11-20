# A plugin's refusal may carry a code the hub can act on

The red runs are in `red-refusal-code.txt`. The plugin API's test failed while a refusal took any
code unchecked. The core's test failed while the core dropped the code and sent words only.

ADR-CVM-010 says the text-message plugin refuses with protocol version 1's `error.json`, code
`permission_missing`, when the owner has taken back the right to read messages. A plugin's
refusal held only its words, so no plugin could give a code. Now `ToolResult.Refused` takes an
optional code, as the mobile architect agreed:

- The code must match `error.json`'s pattern, `^[a-z][a-z0-9_]{0,31}$`, or the refusal is not
  made. So a plugin's typo fails in its own tests, not at the hub.
- With a code, the core sends `error.json` as the call's structured answer, `error` holding the
  words, with `isError` true. This is the same form as the duplicate-call refusal. Without a
  code, the refusal is words only, as before.

`ToolResult.Refused` now holds a check, so it is no longer on Pitest's list of data-only classes.
`tests/test_pitest_data_only.py` failed until it was taken off. That makes the plugin API the
first time Pitest has mutants to measure there. `tests/test_levels.py` failed while the module was
still allowed to have none, so it is off that list in `scripts/levels.py`, and its Pitest run
no longer passes with no mutants. Pitest killed all 5.
