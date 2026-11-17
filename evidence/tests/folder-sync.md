# The folder is synced after a file is replaced whole

The red run is `red-folder-sync.txt`: `replaceWhole` took no folder sync yet.

voice-architect's n1 on #93, and again on #99: after the rename, the folder that holds the
file was not written to the disk, so a power cut just after it could bring the old file back
on some file systems. `replaceWhole` now syncs the folder after the rename. That covers both
the hub file and the call log's rewrite.

If the folder cannot be synced, the write still counts. The new file is already in place, and
an old one coming back after a power cut is a whole file, as before. Failing the write there
would be worse: the gate would refuse a key whose hub is already on the disk.

The tests:
- the folder is synced once, after the rename, when the file already holds the new bytes;
- a folder sync that throws does not undo the write;
- a write that fails before the rename never syncs the folder;
- the real sync works on a real folder.

Pitest on `:core` caught all but main's two old mutants, including the removed sync call, and
the ratchet holds.
