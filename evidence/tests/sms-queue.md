# New text messages wait in a sealed queue until the hub has them

The red run is in `red-sms-queue.txt`. All 31 new tests failed against a queue and a file whose
every body was left unwritten.

ADR-CVM-010's reading part needs a queue on the phone. New messages wait in it, oldest first,
until a later `sms_since` call passes back the cursor of the answer that gave them. This is the
first of the three pull requests in the mobile architect's plan for it. The queue and its file
are plain Kotlin in `plugins/sms/rules`, tested off the phone and mutation-tested (ADR-CVM-007).
`sms_since` itself and the receiver come in the next two.

What the tests pin, in the ADR's own list:

- **Order and ids.** Messages come oldest first. Each id is the install part, then a dash, then
  a number counted up.
- **Masking.** A message that may hold a one-time code is kept only as "a one-time code from
  <sender>", with `masked` set. Its text is never kept. The masking is the existing
  `OneTimeCodes`, run before the message goes in.
- **Acknowledgement.** Passing a cursor back deletes what its answer gave, and only later
  messages follow.
- **A lost answer.** Passing the older cursor again gives the same ids and a new cursor.
- **Pages.** At most 50 messages per answer, with `more` when others wait. Exactly 50 waiting
  gives `more` false.
- **Drops.** Past 500 messages the oldest is dropped and counted. A message older than 7 days is
  dropped and counted, wherever it sits in the queue, and one exactly 7 days old is kept. Exactly
  500 drops nothing.
- **The count.** It is given with every answer until the cursor of an answer that carried it
  comes back. After that answer is lost and an older cursor is passed, the count is still given.
  Drops after an answer stay counted when that answer is acknowledged.
- **Cursors.** An answer that gives a message or a count gives a cursor never given before. An
  empty answer with nothing dropped gives back the cursor it was passed. A cursor the phone never
  gave, one from another install, or one older than a cursor already acknowledged says
  `reset: true` and starts at the oldest message kept. The phone remembers its last 16 cursors;
  an older one reads as never given. The cursor of an empty answer never acknowledges a message
  that came after it.
- **A lost key.** The queue starts again, with a new install part, and the next answer counts
  every message the lost queue held as dropped. Only that count is outside the seal.
- **The file.** It holds no message text or sender in the clear. A save is written beside the
  file, synced, and moved over it, so no half-written file is ever read and none is left behind.
  A damaged file starts a new queue rather than stopping the plugin.

The tests use a seal that only turns each byte, which stands for the phone's Keystore key. The
real seal, AES-GCM with a Keystore key, comes with the receiver, and so does the file's place:
the app's no-backup folder. That folder is already proven to stay out of every backup and
device transfer by `app/src/test/.../NoBackupTest.kt`. Cutting a text to 2000 characters and the
answer's fit to `docs/plugins/sms/sms-since-answer.json` belong to `sms_since`, in the next pull
request.

Pitest killed every mutant in both new files. The first run left five. One was the empty
answer's cursor, which now covers every number given so far; a new test pins that it never
acknowledges a later message. The other four were the closing of in-memory streams, which hold
nothing to close, so they are no longer closed. Every sample is made up: the senders are
invented names and numbers in the unused +47 00 range.
