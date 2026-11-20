# The hub asks for new text messages and gets exactly the agreed answer

The red run is in `red-sms-since.txt`. 43 of the 125 tests failed against parts whose every body
was left unwritten.

This is the second of the mobile architect's three pull requests for reading text messages
(ADR-CVM-010). The first was the queue. This one is the `sms_since` tool. The third, the
receiver, will hand messages in. Everything the tool decides is plain Kotlin in
`plugins/sms/rules`, tested off the phone and mutation-tested.

What the tests pin:

- **The answer's shape.** Every answer the tests make is checked against
  `docs/plugins/sms/sms-since-answer.json` itself. The checker covers the part of JSON Schema
  those files use, as `tests/test_sms_since_shape.py` does in Python. It is shown to refuse 11
  kinds of wrong answer, and to accept the schema's own examples.
- **The input.** The tool's input is the input schema's own shape, and a test compares the two.
  No cursor, or null, starts at the oldest message kept. An empty cursor, one that is not a
  string, or any other argument is refused in words.
- **The time.** It always has its seconds and its offset as `+hh:mm`, never `Z`, as the answer's
  pattern asks.
- **The sender** (task 66). It is cleaned when the message goes in: control and invisible
  characters are dropped, every space or line break becomes one space, and it is cut to 40
  characters. So a letter-named sender, which whoever sends chooses, cannot flood or turn around
  what the hub reads out. A hidden message's line names the sender in that same form. A sender
  with nothing left is "an unknown sender".
- **The kind.** A "+" and digits is a number, and up to six digits is a short code. Seven digits
  or more with no "+" is a national number. A lookup the phone gives puts it in E.164 form, and
  when it cannot, the digits are kept, still a number. Anything else is a name. Whether a message
  is hidden is still decided on the sender as it came.
- **The text.** It is cut to its first 2000 characters, counted as Unicode counts them, so never
  inside a character, and `cut` says so. A hidden message is never cut. The queue's byte format
  is now version 2, and a file of version 1 reads as no queue.
- **The inbox.** The receiver and the tool share the queue under one lock. Every change is saved
  before the call returns, so a cursor handed out, an acknowledgement and a new message all
  survive the app being killed. A poll that changes nothing writes nothing, since the hub polls.
  When the file cannot be written, the answer does not leave.
- **The seal.** AES in GCM mode, so a changed byte anywhere is noticed. The cipher picks the
  nonce, which the Keystore requires. Another key cannot open it, which is how a lost Keystore key
  shows. The tests use a key made in memory. The phone's Keystore key comes with the receiver.

- **What the reviews of the queue found** (#136). Quality and both architects reviewed the
  queue, and five of their points are fixed here, before the hub can read it. Their red run is
  the last part of `red-sms-since.txt`.
  - Drops are now kept as a running total. An acknowledgement only raises how much of it the
    hub has seen. Before, two answers that carried one count, as a hub's retry makes, took it
    off twice when both were acknowledged, and the count could fall below zero.
  - The queue file's start, which sits outside the seal, can no longer mislead. A file whose
    start cannot be read, empty or not ours, counts one message lost rather than none. A count
    there below zero counts one, and one above 500 counts 500. No file at all is still a first
    start, with nothing lost.
  - Bytes that open but cannot be a queue are a lost queue, never a crash. That covers a text
    length below zero or longer than any kept text can be, and a time that cannot be. A lost
    key on a queue that held nothing still counts nothing lost.
  - After the new file is moved in, its folder is synced. A power cut can then never bring back
    the old file, whose numbers would make new messages repeat ids the hub has already seen.
    The phone's sync is an `fsync` on the folder, which the receiver hands in next.

A dropped count may run a little high. A message that was given in an answer the hub never
acknowledged, and is then dropped for age or past 500, is counted as dropped, though the hub may
already have it. The count never runs low. The schema's `dropped` description says so, at the
mobile architect's word (#137).

Two tests could not be seen red against the stubs, because the stubs already did what they ask:
that a hidden message is never cut, and that hiding is decided on the sender as it came. Pitest
shows they are not idle. It killed every mutant in the rules module, and the two survivors
of the first run were dealt with: an unknown sender kind at the edge of the list now has a test,
and a refusal's words became one fixed sentence.

The plugin offers `sms_since` and says it uses `messages` only once it has an inbox. The app
builds it without one until the receiver comes.
