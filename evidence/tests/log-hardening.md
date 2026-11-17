# The log reads damaged lines safely, syncs before a rewrite, and prunes on writes once a minute

The red run is `red-log-hardening.txt`: the two new tests failed.

- **Damaged lines of any shape** (the claude-voice-mobile-architect's note on #91): `[1,2]`,
  a bare string, `{}`, `{"time":7}`, `{"time":"yesterday"}` and `null` are each valid JSON
  but no line this log wrote. Before, the first four threw a `ClassCastException` or a
  `NoSuchElementException` out of `record`, so the call being logged failed. Now each is
  dropped at the next prune, and the call is logged as usual.
- **Pruning on writes at most once a minute** (voice-architect's n1): three writes in one
  minute read the file once, and a write a minute later reads it again. Past the cap, the
  log is pruned within the minute too. The test pins the boundary: at the cap, no prune; one
  past it, a prune.
- **Synced before the rename** (quality's n1 and voice-architect's n2): the rewrite opens the
  new file with `StandardOpenOption.SYNC`, so its bytes are on the disk before it replaces the
  log. No unit test can watch a power cut. Using an open option, not a separate `sync()`
  call, also leaves Pitest no call to remove.

Pitest on `:core` caught all but main's two old mutants. A first version left a boundary
mutant on the cap, now pinned by the test, and raised `record`'s complexity to 4; `write`
and `due` were split out until the ratchet held.
