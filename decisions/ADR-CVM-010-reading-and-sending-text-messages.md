# ADR-CVM-010: Reading and sending text messages

## Status

Proposed 2026-11-18 by the architect session. It writes down the phone's part
of the messages design and its build plan, which the owner approved on
2026-11-17, relayed by the manager session. Reviewed by the quality and voice
architect sessions. Where this record goes beyond that design, the pull request
says so, and the owner decides that part.

## Context

- The owner wants to hear new text messages by voice, and to answer them by
  voice, on the same terms as the other message networks. The laptop side,
  `messages-mcp` and its `sms-bridge`, is built in its own repository. This
  record is the phone's side only.
- Protocol version 1 has the hub call the phone, never the other way round.
  So the phone keeps new messages until the hub asks for them. It never sends
  them on its own.
- Text messages hold other people's words and one-time codes. The protocol's
  next version names that private thing `messages`, "reads or sends the
  owner's text messages". This app accepts only listed names (ADR-CVM-003), so
  neither module is switched on before that name is listed.
- `RECEIVE_SMS`, `READ_SMS` and `SEND_SMS` are hard-restricted. The Play Store
  allows them only for the default text app. This app is sideloaded, so that is
  no bar. But after an install from a browser or a file manager, Android may
  first ask for "Allow restricted settings" in the app's own info page before it
  lets the owner grant them.

## Decision

**Two plugins and one capability** (ADR-CVM-007):

- `plugins/sms` reads. Its `uses` is `["messages"]`. It declares
  `RECEIVE_SMS` and `READ_SMS`, never `SEND_SMS`.
- `plugins/sms_send` sends. Its `uses` is `["messages"]`, and its confirm tool
  is in `confirms`. It declares `SEND_SMS` and `READ_SMS`. It needs `READ_SMS`
  for the sent-before check below.
- `capabilities/smsstore` reads the phone's own message store and turns
  numbers into E.164 form, which is the full international form with the
  country prefix. Both plugins use it, and neither uses the other. To
  `sms_send` it answers one question only: "has the owner sent a message to
  this E.164 number?", as a yes or no. It never hands it a message, a list or
  a count. So code in the send path that keeps to the capability cannot read
  a one-time code. The app holds `READ_SMS` as a whole, though, so this keeps
  the code in order and is no security wall (ADR-CVM-007).

Each has its own build flag and its own switch, and both are off when first
installed. A build with `sms` alone holds no permission to send.

### Reading: `sms`

**What it sees.** It sees plain text messages as they arrive, through
Android's `SMS_RECEIVED` broadcast, to a receiver declared in the manifest.
Android delivers that broadcast even when the app is not running. The receiver
reads the message and does nothing else. The default text app gets every
message as before. The module does not see RCS chats, which the default app
keeps in its own storage, or a message that app has deleted. Picture messages
(MMS) are left for a later step.

**The queue.** Each message goes into a queue on the phone, kept in the app's
no-backup folder and encrypted with a key in Android's Keystore. The queue
holds at most 500 messages, or 7 days. Past either limit it drops the oldest
message and counts the drop. If the key is lost, the queue is dropped, and the
next answer counts those messages as dropped.

**One tool, `sms_since(cursor)`.** It returns the messages after `cursor`, in
the order they arrived, and the count dropped since the last call. It gives at
most 50 messages per answer, and says when more are waiting. A message leaves
the queue only when a later call passes a cursor at or past it, so an answer
lost on the way is sent again. Each message carries its sequence number, the
sender (E.164, or the sender's name for a letter-named sender), the time it
arrived as an ISO 8601 time with its offset, and its text. The text is marked
as coming from outside the phone. `sms-bridge` on the laptop calls this tool
through the hub. It marks the tool as a poll, so an empty answer is counted,
not logged (ADR-CVM-003).

**One-time codes never leave the phone.** Before a message enters the queue,
the module masks it, and it masks too much rather than too little:

- from a short-code or letter-named sender, every run of 4 to 8 digits. A
  short code is a sender of at most 6 digits with no "+". This narrows the
  rule: a sender of 7 or 8 digits with no "+" is often an ordinary number
  written without its country code. Counting it as a short code would mask
  the digits in every contact's texts. So a bare code from such a sender,
  with no code word near it, goes out unmasked;
- from any sender, a run of digits near a code word, before or after it, on
  the code word's own line, the line before it or the line after it, so
  "123456" above "is your code" is masked too. The code words are matched in
  any case and inside longer words: "code", "kode", "otp", "one-time",
  "passcode", "verification", "bekreftelse", "passord", "pin", "engangskode",
  "sikkerhetskode", "logg inn", "login", "bankid" and "token".

Both rules read a cleaned copy of the text: Unicode's compatibility form,
split (NFKD), with accents and other combining marks dropped, invisible
characters dropped and every dash or minus sign read as "-". So "códe" reads
as "code". None of the code words holds an accent or æ, ø or å, so the
cleaning never changes them. A digit is any decimal digit, in any script. The cleaned copy is only
for matching. What is masked is the original message, whole.

A masked message is sent as "a one-time code from <sender>", with `masked:
true`. The whole message is still in the phone's own text app, where the
owner reads the code.

### Sending: `sms_send`

It sends by drafting first, with an undo delay, and with no tap on the phone.

**`sms_send_request(to, text)`** sends nothing. It stores a draft on the phone
and answers with a `token`, a `version` and a `say` line. The line reads back
the recipient, the text, and how many messages the text becomes. The phone
counts those with Android's own `divideMessage`. The recipient is named by
the last four digits of the number ("the number ending 1234"), since the module
does not read contacts. A changed draft to the same recipient gets a new
version and a new read-back. A draft to another recipient replaces the open
one and says so. A draft lapses 15 minutes after its last change. The token
is bound to the E.164 number, a hash of the text and the version.

**What may be drafted.** Anything else is refused, with the reason:

- only a full mobile number in the SIM's own country, which the owner has sent
  a message to before, found in the sent box through `smsstore`. Both numbers
  are compared in E.164, so a number with and without its country prefix still
  matches. Short codes, premium and shared-cost numbers, foreign numbers and a
  number seen only in a received message all fail. The mobile ranges come
  from a table in the code, one per country. A SIM country with no table
  refuses every send;
- at most 1000 characters, with no link and no run of more than 6 digits;
- no more than 10 sends an hour, 30 a day, and 3 to one recipient in 10
  minutes, counted on the phone as well as on the laptop.

**`sms_send_confirm(token, version)`** is the plugin's one confirm tool, so the
hub calls it only for the voice. A version other than the latest is refused
with "it changed; read it back first". On a match the phone holds the message
for the undo delay, and answers at once: "Sending in 10 seconds. Say stop to
cancel." When the delay ends, it sends what it stored, never a number or text
from the confirm call.

**`sms_send_cancel(token)`** stops a held message. Cancel and the timer take
one lock. The timer sends only if the message is still held, so each message
ends either sent or stopped, never both or neither. A cancel after the send
answers "too late, it was sent at 14.02".

**A held message survives nothing.** It lives only in the app's memory. When
the app stops, restarts or crashes, the message is stopped and never sent
later. When a hold starts, the phone writes a marker with the token and the
time, and nothing else. On the next start, a marker still there becomes a
"stopped: the app restarted" outcome. So a crash is reported, not silent.

**`sms_send_outcomes(cursor)`** tells the laptop how each held message ended,
so its whats_new can say "sent at 14.02" or "stopped, not sent". It is
`sms_send`'s own poll, with the same cursor, acknowledgement and resend as
`sms_since`. A phone with sending on and reading off still reports outcomes.
Each item holds the token, the outcome (`sent`, `stopped` or `failed`), the
time and a reason in words. It never holds a number or a text. The laptop
marks it as a poll, so an empty answer is counted, not logged.

**The undo delay** is a phone-only setting (ADR-CVM-003). It defaults to 10
seconds and has a floor of 5 seconds in the code. No tool reads or changes it.

**How it sends.** It uses Android's default text subscription. With none set,
it refuses and says where to set one. It never picks a SIM itself. A long text
goes as the parts `divideMessage` gives. Android's own warning for premium
numbers stays on. The notification for a send is private: the lock screen
says only "sent a text message".

**What the call log keeps.** As for every plugin that reaches a private thing,
it keeps the time, caller and outcome of each call, never a number or a text.

## Consequences

- The owner hears new text messages by voice, a few seconds to two minutes
  after they arrive, without the phone ever calling out.
- One-time codes stay on the phone. A message wrongly masked can still be read
  in the phone's own text app.
- A friend on RCS mostly does not show up, and the voice cannot say so for a
  given friend.
- A voice that a stranger's message has steered could still send, within the
  limits above, to numbers the owner already writes to. It could not send a
  link, a code, or anything to a new number. The read-back, the version check
  and the undo delay are what the owner hears, and the phone's spoken hold, in
  a record of its own after it is measured, would add a voice the model cannot
  silence.
- Tests, written first, as plain JVM tests with fakes for Android's classes:
  - masking, on a set of real-shaped samples with made-up numbers. Bank,
    delivery and login texts are masked, including a code before its word,
    a word on the line before the code, and a code on the line before its
    word. A sender of 6 digits counts as a short code, and one of 7 does
    not. An 8-digit sender's bare code with no code word is listed as
    passing unmasked, so the count shows what the bound gives up. Texts with
    dates or prices are listed too. How many of them were
    masked goes in the CI run's artifact, never in the repository;
  - `smsstore`: nothing it offers `sms_send` returns a text, a list or a
    count;
  - the queue: the order, the cursor's acknowledgement, a lost answer sent
    again, the 500 and 7-day drops and their count, a lost key, and nothing in
    a backup;
  - drafts: a new version per change, a stale version refused, a lapsed
    draft dropped, and one draft per recipient;
  - the allowlist: a short code, a foreign number, a premium number, a new
    number and a number seen only in a received message are refused; the
    same number with and without its prefix matches;
  - the race: cancel and the timer fire together over many runs, each ending
    in exactly one outcome; and a stopped app sends nothing;
  - outcomes: each is given exactly once after its acknowledgement, a kill
    during the hold gives a "stopped" outcome after the restart, and the
    outcome store holds no number or text;
  - the delay: below 5 seconds is refused when the setting is saved;
  - a build without `sms_send` has no `SEND_SMS` in its merged manifest.

## Related

- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): phone-only settings,
  the call log, and empty polls.
- [ADR-CVM-007](ADR-CVM-007-modules-each-install-picks.md): modules, build
  flags and switches, and capabilities.
- [claude-voice `docs/agent-protocol/next-version.md`](https://github.com/roykollensvendsen/claude-voice/blob/main/docs/agent-protocol/next-version.md):
  the `messages` name.
