# agent_info refuses by name when a plugin reports an unknown use

The red run is `red-unknown-use.txt`.

The claude-voice-mobile-architect's note on #95: when a plugin's `uses` turns to a name v1
does not know while the app runs, `entries()` threw. The hub got a bare `500`, and nothing
was logged, because the report failed before the call's outcome was written.

Now `agent_info` first asks `Plugins.unknownUse()`. If a plugin reports an unknown name, the
answer is a refusal that names the plugin, and the call is logged as `refused`. The test turns
a plugin's `uses` to `micropone` between two calls. The first answers, and the second refuses,
naming `phone` but not passing on the plugin's own word. The log reads `answered, refused`.
The check in `entry()` stays as the last guard, for a plugin that changes between the two
reads.

Pitest on `:core` caught all but main's two old mutants, and the ratchet holds.
