# The owner confirms the phone's name before the first pairing

The red run is `red-machine-name.txt`: `MachineName`, `TextStore` and `Confirmed` did not exist yet.

claude-voice-mobile-architect's note (1) on #101: two phones of one model would get the same
suggested name, and the hub knows a phone by its name. So the owner now confirms it, on the
app's first screen, before anything can pair. The plan was agreed with the architect, with two
additions it asked for: the screen says why in words, and once a hub is paired the core itself
refuses a different name.

- `MachineName` keeps the confirmed name in `machine.json`, as `{"version":1,"name":"…"}`, in
  the no-backup folder, written whole like `hub.json`. Only the owner's screen confirms it, and
  no tool the app offers reaches it. It is kept apart from the privacy settings, since it is the
  phone's identity, its own field in v1.
- `OnceNamed` builds the agent only once a name is confirmed, and then the same one for good. So
  `App.agent()` is null before, and nothing can pair under a name the owner did not choose.
- `TextStore` is what both kept files are; `FileHubStore` is now `FileTextStore`.
- The screen (`NameStep`) shows the name prefilled from the phone's model, says why it matters,
  and answers each outcome in words, in English and Norwegian.

The tests:
- no name before one is confirmed; a confirmed name is kept with its version and read back;
- a name the protocol does not take is refused, and nothing is kept; 32 characters is taken;
- once paired, a different name is refused, the same one is kept;
- a name that cannot be written is not kept, and says so;
- a kept file that cannot be read, has another version, or holds a bad name is no name;
- nothing is built before a name, then one thing once, with that name;
- every outcome has its own words on the screen.

Also voice-architect's n1 on #104: a comment on why `agent_info`'s address can be empty.

**Coverage.** Pitest on `:core` caught all but main's two old mutants. The ratchet says coverage
fell from 90.4 to 87.9: the new screen's views, like `MainActivity`'s, cannot run in a JVM test.
Its one piece of logic, which words go with which outcome, is in `NameAnswer` and tested. The
PR body carries the ratchet exception.
