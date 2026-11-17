# What each plugin uses is read once per agent_info

The red run is `red-uses-read-once.txt`.

voice-architect's n1 on #97: `agent_info` read each plugin's `uses` twice, once to check it and
once for the report. A plugin that changed between the two reads still gave a bare `500`. Now
`Plugins.usesNow()` reads it once, and the same list goes to both `unknownUse()` and the report.

The test's plugin reports `calls` at its first read and `micropone` at any later one. The
answer is `200` with `uses: ["calls"]`, and the plugin was read exactly once. Before, the
second read threw (`phone now uses [micropone]`).

**A race in an older test, fixed on the way.** The red run also failed the queued-call test
from #91: it read the log right after the fifth call, before all four held calls had logged
`late`. Each held call logs when its own wait ends, in no set order. The test now reads the
log only after the four threads have ended. The core tests then passed three runs in a row.

Pitest on `:core` caught all but main's two old mutants, and the ratchet holds.
