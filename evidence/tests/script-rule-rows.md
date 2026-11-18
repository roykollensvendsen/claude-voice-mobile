# Every rule the repository's own scripts enforce is switched off by a row

The red run is `red-script-rule-rows.txt`. Once the evidence test read `scripts/`, it found
nine rules marked there with no row.

`tests/test_rules_have_evidence.py` read `# RULE:` markers only from `src/`, which this
repository does not have. Its app code is Kotlin, guarded by Pitest. So the rules in the
scripts (two in `imperative.py`, six in `mutate.py`, and #107's in `no_session_links.py`)
were marked, but nothing required a row for them, and nothing switched them off.

Now:
- the evidence test reads `scripts/*.py` as well as `src/`;
- `scripts/mutations.toml` has a row for each of the nine;
- `tests/test_mutation_runner.py` has the tests four of the runner's rules lacked. Three are
  ported from mister-mcp, whose rows for the same rules these follow. The fourth, for an empty
  rule table, is new.

`scripts/mutate.py` switches each rule off in turn. Its result is a measurement, so it is not
kept here: its own CI job runs it on every pull request and keeps the report as the artifact
`rule-mutations`, and the pull request's body gives the run it was opened with.
