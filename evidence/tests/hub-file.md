# The paired hub is kept in a file written whole

The red run is `red-hub-file.txt`: `FileHubStore` did not exist yet.

`KeptGate` keeps the paired hub through a `HubStore`, but only the tests had one. `FileHubStore`
is the real one: one file, read whole, and replaced whole on each pairing through
`replaceWhole()`. That writes `<name>.new` through to the disk and renames it over the file. The
call log's rewrite now uses the same function, so both files are written the same way.

The app puts the file in `noBackupFilesDir`, as the claude-voice-mobile-architect asked. The data
extraction rules already exclude every folder, and `NoBackupTest` checks that.

The tests:
- no file reads as no hub;
- a pairing is read back by a new store on the same file, as after a restart, with no `.new`
  file left behind;
- a new pairing replaces the old one whole;
- a write that cannot be put in place throws `IOException` and keeps the hub before;
- a `KeptGate` built on the file after a restart knows its hub, with no problem.

Pitest on `:core` caught all but main's two old mutants, and the ratchet holds.
