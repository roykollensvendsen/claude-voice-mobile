# A commit that links to a Claude session is refused

The red run is `red-no-session-links.txt`: the test was ported first, and its module did not
exist yet.

The manager found that #50 and #68 passed CI green with `Claude-Session:` lines in their commits.
The commits job ran only `committed` and `scripts/imperative.py`, and neither looks for them.
Such a line points into one person's account, so to everyone else reading this public history
it is a dead link.

`scripts/no_session_links.py` and its test are ported from mister-mcp, which is also
GPL-3.0-or-later. It refuses a message with a `Claude-Session:` line, in any case, or a
`claude.ai/code/session_` address. `Co-Authored-By:` and ordinary mentions of claude.ai pass.

It runs:
- in the commits job, over the same range as the other two checks;
- in `.githooks/commit-msg`, so a message is refused before it is committed.

Checked by hand: the hook passed main's last commit message and refused one with a
`Claude-Session:` line. The script found no such line in main's last 20 commits. The Python
suite passed: 102 tests, 1 skipped, as CI runs it.

The rule's row in `scripts/mutations.toml` comes in the next PR. The evidence test there reads
`# RULE:` markers only from `src/`, which this repository does not have. So none of the
scripts' rules has a row yet, and a row for this one alone would turn that test red.
