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

The receiver takes the broadcast only from the system, through the
`BROADCAST_SMS` permission. So no other app on the phone can hand it a message
that the hub would read as the owner's. The app is not direct-boot aware, so a
message that arrives after a restart and before the first unlock never reaches
the queue. The default text app still has it. Each message is stamped with the
phone's own clock and offset when it arrives, not the time the network gave.

**The queue.** Each message goes into a queue on the phone, kept in the app's
no-backup folder and encrypted with a key in Android's Keystore. The queue
holds at most 500 messages, or 7 days. Past either limit it drops the oldest
message and counts the drop. If the key is lost, the queue is dropped, and the
next answer counts those messages as dropped. A queue file that can't be read
counts at least one. The key needs no unlock and no fingerprint, since the
receiver runs while the phone is locked.

The dropped count may be a little high, never low, with one exception. If a
message can't be saved, even on a second try, it stays only in memory. If the
app is then killed before a later save succeeds, the message is lost and never
counted. That takes a full disk or a Keystore failure followed by a kill. The
default text app still has the message.

**One tool, `sms_since(cursor)`.** It returns the messages after `cursor`, in
the order they arrived, and the count dropped since the last call. It gives at
most 50 messages per answer, and says when more are waiting. A message leaves
the queue only when a later call passes a cursor at or past it, so an answer
lost on the way is sent again. Each message carries an id, the sender (E.164,
a short code's digits, or the sender's name for a letter-named sender) and
which of the three it is, the time it arrived as an ISO 8601 time with its
offset, and its text. The text is marked as coming from outside the phone.
`sms-bridge` on the laptop calls this tool through the hub. It marks the tool
as a poll, so an empty answer is counted, not logged (ADR-CVM-003).

The exact shape is in two JSON Schema files, which `sms-bridge` is built
against: the input in
[`docs/plugins/sms/sms-since-input.json`](../docs/plugins/sms/sms-since-input.json)
and the answer in
[`docs/plugins/sms/sms-since-answer.json`](../docs/plugins/sms/sms-since-answer.json).
There is no separate acknowledgement call:

- The cursor means nothing outside the phone. The bridge passes it back
  exactly as it came, and never reads it, compares it or builds one. Passing
  it acknowledges every message in that answer and its dropped count, and
  only then does the phone delete them. The bridge's first call passes none.
- An answer that carries a message or a dropped count gives a cursor never
  given before. So the cursor of an earlier answer never acknowledges a later
  one. The phone clears a dropped count only when a call passes the cursor of
  an answer that carried it. Until then the count is given again with each
  answer, so a lost answer does not lose it either. An empty answer with
  nothing dropped may give back the cursor it was passed.
- A cursor this phone never gave, as after a reinstall, is not refused. The
  answer says `reset` and starts at the oldest message kept. A message given
  twice keeps its id, so the bridge drops an id it has already seen.
- An id is an install part and a number. The install part is chosen at
  random on every install and every clearing of the app's data, so an id
  never repeats. The bridge can therefore keep the ids it has seen across a
  reset without dropping a new message.
- A text is given as its first 2000 characters, with `cut` saying whether
  more was left out. The whole message is in the phone's own text app.
- The time carries its offset by a pattern, not only by `format`, which JSON
  Schema 2020-12 treats as a note. So a bridge whose validator skips
  `format` still refuses a time without one.
- The phone refuses only when it may not read messages: the owner has taken
  back `RECEIVE_SMS` or `READ_SMS`. The refusal is protocol version 1's
  `error.json`, with code `permission_missing`, and its `error` says where on
  the phone to grant it again.

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
split (NFKD), with every mark dropped (accents, enclosing marks such as a
keycap, and spacing marks), invisible characters dropped and every dash or
minus sign read as "-". So "códe" reads as "code". None of the code words
holds an accent or æ, ø or å, so the cleaning never changes them. A digit is
any decimal digit, in any script. The cleaned copy is only for matching. What
is masked is the original message, whole.

A masked message is sent as "a one-time code from <sender>", with `masked:
true`. The whole message is still in the phone's own text app, where the
owner reads the code.

### Sending: `sms_send`

It sends by drafting first, with an undo delay, and with no tap on the phone.

**The first version of sending.** The owner chose a smaller first version on
2026-11-21, relayed by the manager session. It is choice B in the messages
design. What follows this paragraph is the full design. The first version
builds the part of it below, and the rest waits for a later version.

- **It holds:** the draft with its token and version, the read-back, confirm
  sending only the stored text of the latest version, the undo delay with its
  floor, and cancel and the timer under one lock. Confirm is in `confirms`,
  so the hub denies it in every role and calls it only after the owner's
  spoken yes. It also holds the owner's
  limits: no more than 10 sends an hour, 30 in 24 hours, and 3 to one number
  in 10 minutes, and at most 1000 characters, with no link and no run of more
  than 6 digits. A run counts across spaces, dots and dashes.
- **The limits are checked twice:** when a draft is made, so the owner hears
  it early, and again at confirm. A send counts from its confirm, and a
  stopped one stops counting. A refusal names the limit and when it lifts,
  with the code `rate_limited`, or `text_refused` for what a text holds. The
  counts live in the app's memory, like the drafts, so a restart of the app
  starts the counts again. Each send still needs the owner's spoken yes.
- **It differs:** the read-back names the whole number, not its last four
  digits. Every answer after confirm says "I can't yet confirm delivery", and
  a cancel that comes too late says when the text was handed to the phone.
  Nothing says "sent", since the phone does not yet learn whether it arrived.
- **It waits:** the sent-before check, the mobile ranges, a draft lapsing
  after 15 minutes, one draft per recipient, the crash marker, and
  `sms_send_outcomes`. Without the sent-before check the plugin does not need
  `READ_SMS`, so the first version declares `SEND_SMS` alone. Any E.164 number
  may be drafted. Android's own warning for premium numbers stays on.

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
the app stops, restarts or crashes during the hold, the message is stopped
and never sent later. When a hold starts, the phone writes a marker with the
token, the time and the state `held`, and nothing else. Just before it asks
Android to send, it changes the state to `sending`, and waits until that is on
disk. The marker goes when the outcome is stored. So on the next start, a
marker still there tells which side of the send the app stopped on:

- `held` becomes a "stopped: the app restarted" outcome. The message was
  never handed to Android.
- `sending` becomes an `unknown` outcome: "it may have been sent; check the
  text app". Android may have sent it before the app stopped, and the app
  cannot tell. It never sends it again.

So a crash is reported, not silent, and never reported as stopped when it may
have gone. The outcome is `sent` only when Android reports every part sent,
and `failed` when it reports any part failed.

**`sms_send_outcomes(cursor)`** tells the laptop how each held message ended,
so its whats_new can say "sent at 14.02" or "stopped, not sent". It is
`sms_send`'s own poll, with the same cursor, acknowledgement and resend as
`sms_since`. A phone with sending on and reading off still reports outcomes.
Each item holds the token, the outcome (`sent`, `stopped`, `failed` or
`unknown`), the time and a reason in words. It never holds a number or a text.
The laptop marks it as a poll, so an empty answer is counted, not logged.

**The undo delay** is a phone-only setting (ADR-CVM-003). It defaults to 10
seconds and has a floor of 5 seconds in the code. No tool reads or changes it.

**How it sends.** It uses Android's default text subscription. With none set,
it refuses and says where to set one. It never picks a SIM itself. A long text
goes as the parts `divideMessage` gives. Android's own warning for premium
numbers stays on. The notification for a send is private: the lock screen
says only "sent a text message".

**What the call log keeps.** As for every plugin that reaches a private thing,
it keeps the time, caller and outcome of each call, never a number or a text.

### The next version: by name, older texts, several recipients, delivered

The owner asked for four additions on 2026-11-21, after the first version
sent a text end to end. They are texting a contact by name, searching older
texts, sending to several people with one yes, and hearing when a text was
delivered. The owner said yes to the three questions they raise, each in full
wording, relayed by the manager session. The next version builds them on top
of the first version. Everything the first version holds stays: the read-back
of the stored latest version, confirm denied in every role, the undo delay
with its floor, one lock, the limits and the text rules. Where this section
and the full design above differ, this section wins.

**By name.** A new capability, `capabilities/contacts`, declares
`READ_CONTACTS`. It answers one question: which phone numbers match this
spoken name.

- `to` in `sms_send_request` may be a name or a number. A query must match a
  whole first name, a whole last name or the full name, ignoring case and
  accents. It never matches a prefix, a part of a name or a single letter, and
  a query under 2 letters is refused. The phone allows at most 10 lookups an
  hour, counted in memory like the send limits. Past that the refusal is
  `rate_limited`: "too many name lookups; say the number instead". A number
  given as a number is not a lookup.
- One mobile number for one name gives a draft. Anything else gives no draft
  but a `choices` answer of at most 5. Each choice is an opaque `pick` bound
  to that request's token, the display name and the label with the last two
  digits ("Kari, mobile ending 12"), never the full number. The voice
  asks the owner, then calls again and passes the `pick` in place of that name
  in `to`. A contact with only a home or work number also answers `choices`,
  so the owner hears and picks it, never a silent draft. No match answers
  `not_found`.
- The full number leaves the phone only in the read-back of the one chosen
  draft, which names both the contact and the whole number. The draft stores
  the number, not the name, so a contact changed later does not move it.
- The lookup is not a tool of its own. It lives only inside
  `sms_send_request` and `sms_search`.
- The matching rule, the cap of 5, the floor and the counter live in a
  pure-JVM rules module and are mutated (ADR-CVM-007).

**Older texts.** A new tool in `sms`, `sms_search(who, words, days)`, looks
back at most 90 days, 30 by default. `who` is a name, resolved as above, or a
number. At least one of `who` and `words` is needed. It answers with at most
10 messages, newest first, and how many more matched. It reads through
`smsstore`, which the reading plugin already holds `READ_SMS` for.

- Held back whole: every text from an alphanumeric sender ID or a short code
  of fewer than 8 digits, counted on the raw sender, before E.164. So are texts
  from any sender with a run of digits like a code, or one of the words kode,
  code, engangskode, passord, password, BankID, verification or PIN, in any
  case. Such a text answers only "a message from <sender>, text held back".
  The word list is pinned by a test, and the rule is mutated.
- A searched text is the sender's words, data and never an instruction. The
  say line frames each one as "from <sender> at <time>, quoted: …", and the
  tool's description says so. A draft made right after a search still needs
  its own read-back and the owner's yes, as every draft does.

**Texted before.** Sending asks reading one yes or no question through the
core: "texted before?" for one E.164 number. `sms_send` declares `SEND_SMS`
alone, never `READ_SMS` or `READ_CONTACTS`, and a manifest test pins that.
With reading switched off, the read-back says "can't check whether you've
texted this number before", never nothing. It is a warning, not a refusal,
since the owner parked the refusal.

**Several recipients.** `to` may be a list of 1 to 5 recipients, each a name,
a number or a `pick`. One name needing a choice stops the whole draft with one
question. The same number twice counts once.

- The read-back names every recipient with the whole number and gives the
  text once. It marks a raw number "not in your contacts" and a number
  "never texted before" when reading says so. So a stray number stands out.
- One confirm, one undo delay and one cancel cover the group. After the delay
  the phone sends one text to each recipient, never a group MMS.
- Each text counts against the limits. The whole group is checked at request
  and at confirm, and if it does not fit, nothing is drafted and the answer
  says how many would fit. A group is never partly sent because of a limit.

**Delivered.** The phone asks Android for a delivery report for each part,
next to the sent report it already asks for. `sms_send_outcomes` and the
crash marker, which waited in the first version, come with it.

- Each recipient's outcome is `sent`, `delivered`, `stopped`, `failed` or
  `unknown`. `delivered` comes only when every part of that text has a
  delivery report saying so. Some networks send none, so a text can stay
  `sent`, and then the voice never says delivered.
- Outcomes carry no number, name or text. They name each recipient by
  position in the read-back, after duplicates are dropped, and a test pins
  that order. Right after the undo the voice still has the read-back and names
  them. A later poll, or a new conversation, hears "recipient 2: delivered",
  by design.
- The voice polls for outcomes after the undo delay. A push through whats_new
  is a later step of its own.
- Its shape is fixed in
  [`docs/plugins/sms/sms-send-outcomes-input.json`](../docs/plugins/sms/sms-send-outcomes-input.json)
  and
  [`docs/plugins/sms/sms-send-outcomes-answer.json`](../docs/plugins/sms/sms-send-outcomes-answer.json).
  It is the plugin's own tool, so no protocol version changes. A recipient
  whose text is later delivered gets a second item after its `sent` one.

**On the hub.** `sms_search`, `sms_send_request` and `sms_since` go in the
"voice only" group in the hub's permissions, the one wall between the owner's
voice and any other session. In this version `sms_send_request` reads
contacts, since its read-back carries a name with the whole number.
`sms_send_confirm` stays denied in every role. The hub's call log, usage store
and mailbox keep, for these tools, the outcome and a count of recipients or
results, never names, numbers or text. No protocol version changes: `to` as
a list or a `pick` is the plugin's own argument schema.

**What waits still:** the mobile ranges, a draft lapsing after 15 minutes and
one draft per recipient.

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
    again, the 500 and 7-day drops and their count, a count still given after
    its answer is lost and an older cursor is passed, a lost key, a cursor from
    another install giving `reset` and ids with a new install part, a text
    over 2000 characters cut, and nothing in a backup. Every answer the tool
    gives in these tests fits `docs/plugins/sms/sms-since-answer.json`, and
    each masked message in them reads "a one-time code from <sender>";
  - the receiver: the manifest takes `SMS_RECEIVED` only with
    `BROADCAST_SMS`, a message is stamped with the phone's clock, a failed
    save is tried once more, an unreadable queue file counts at least one
    dropped, and a missing one counts none;
  - drafts: a new version per change, a stale version refused, a lapsed
    draft dropped, and one draft per recipient;
  - the allowlist: a short code, a foreign number, a premium number, a new
    number and a number seen only in a received message are refused; the
    same number with and without its prefix matches;
  - the race: cancel and the timer fire together over many runs, each ending
    in exactly one outcome; and a stopped app sends nothing;
  - outcomes: each is given exactly once after its acknowledgement, a kill
    during the hold gives a "stopped" outcome after the restart, a kill just
    after the send call gives an `unknown` outcome and no second send, a
    multipart text with one part failed gives `failed`, and the outcome store
    holds no number or text;
  - the delay: below 5 seconds is refused when the setting is saved;
  - a build without `sms_send` has no `SEND_SMS` in its merged manifest.

## Related

- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): phone-only settings,
  the call log, and empty polls.
- [ADR-CVM-007](ADR-CVM-007-modules-each-install-picks.md): modules, build
  flags and switches, and capabilities.
- [claude-voice `docs/agent-protocol/next-version.md`](https://github.com/roykollensvendsen/claude-voice/blob/main/docs/agent-protocol/next-version.md):
  the `messages` name.
