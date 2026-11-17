# The log of every call (ADR-CVM-003)

The red run, with stubs in place so the tests compiled, is `red-call-log.txt`: 23 tests
failed. Seven guard tests passed even against the stubs, because doing nothing already
satisfies them: nothing rewritten, nothing logged for a non-call, no inputs in the log, a
failing disk never stops a call, clear(), an empty log, and a missing file.

- **A line** holds exactly `time` (UTC), `tool`, `hub`, `caller`, `outcome` and `error`, in
  that order. `error` is the exception's class for `broke`, and null otherwise (`CallLogTest`).
- **Seven outcomes:** `answered`, `refused`, `late`, `broke`, `not_run`, `duplicate` and
  `busy`. In `McpHandlerTest` each is logged by the path that makes it: a plugin's answer
  and refusal, an unknown tool (refused), `agent_info` (answered), a duplicate id, kept ids
  full (busy), past the limit (late), a plugin that throws (broke, class only), and a call
  that waited for a thread (not_run).
- **Never inputs or answers.** A call with `{"seconds":7}`, an answer with `percent: 81`, a
  refusal's words and a thrown message all leave no trace in any line.
- **Text from the hub** (caller, tool name) is cut to 64 characters, loses control
  characters, and never ends in half a surrogate pair. A quote or newline cannot break a
  line, since every line is encoded as JSON.
- **30 days and 5 000 lines.** A line older than 30 days goes when a line is written, at the
  app's start (`prune()`), and whenever the log is read (`entries()`). The test checks the
  store right after the write, since `entries()` prunes too: Pitest showed that a missing
  prune on write was otherwise invisible. A line just under 30 days stays. The 5 000-line
  cap is tested with a cap of 3, and the two numbers are pinned in their own test.
- **Nothing is rewritten** when nothing needs pruning. A line a crash cut short is dropped at
  the next prune, and the rest are kept.
- **The file:** append writes one line; a rewrite writes `calls.jsonl.new` and renames it
  over the log, leaving no new file behind; a failed rename throws. A full disk while
  logging never stops the call.

Locally: `./gradlew check` is green, and the core tests passed three runs in a row.
`python3 scripts/ratchet.py check --base origin/main` holds. Pitest's only survivors are
main's two old ones.
