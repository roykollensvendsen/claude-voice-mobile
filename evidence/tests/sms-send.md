# The phone sends a text for the hub, only after the owner's yes and an undo delay

The red run is in `red-sms-send.txt`. All 14 tests of the sending rules failed against parts
whose every body was left unwritten, and the test that pins the tool list failed against a
phone that marked every tool read-only for the run.

This is choice B of messages-v1 §8: a separate plugin, `sms_send`, so a build that should only
read texts never holds the permission to send them. Everything it decides is plain Kotlin in
`plugins/sms_send/rules`, tested off the phone and mutation-tested.

What the tests pin:

- **The names.** The plugin offers exactly `sms_send_request`, `sms_send_confirm` and
  `sms_send_cancel`, the names the hub's deny rule matches, and only confirm needs the owner's
  spoken yes. A plugin's package ends in its name, so detekt's package rule now allows `_`.
- **Never read-only.** The phone's tool list carries only a name, a description and an input,
  never annotations, so the hub never marks one of the three read-only. A core test pins it.
- **A request sends nothing.** It keeps a draft in memory and says "Draft to <to>: '<text>'",
  with a token and a version. A new text to the same number while the draft is held is its next
  version under the same token. A number not in E.164 form is refused with
  `invalid_recipient`, and an empty text is refused in words.
- **Confirm sends the stored text only.** Confirm takes a token and a version, and no text of its
  own; a call that brings one is refused. Any version but the latest is refused with
  `stale_version`, "It changed; read it back first." An unknown, stopped or already confirmed
  token is refused with `no_draft`. Its say is exactly "Sending to <to> in <N> seconds. Say stop
  to cancel. I can't yet confirm delivery."
- **The undo delay.** 10 seconds by default. An outbox made with a delay under 5 seconds is
  refused when it is made; 5 seconds itself is allowed.
- **Stop and the timer.** They take one lock, and the timer sends only if the draft is still
  armed. Stop before the timer says "Stopped, not sent." and nothing goes. Stop after it says
  "Too late to stop; it was handed to the phone at HH.MM. I can't yet confirm delivery." The
  race test runs stop and the timer on two threads 200 times, and each time exactly one wins.
  Nothing ever says "sent".
- **A refused send** is logged on the phone as `send_failed`, with no number or text, and never
  thrown on. The hub hears no outcome yet.
- **The permission.** The plugin declares `SEND_SMS`. Without it, each of the three tools is
  refused with `permission_missing` and the Settings steps, including "Allow restricted settings"
  for a sideloaded app.

The first mutation run killed 62% of the mutants. No test had called the tools through their
input check with good arguments, and one survivor could not be told apart: the timer's task,
a lambda that returns nothing, mutated to return null. `SmsSendTest` was added for the first:
each tool goes through to the outbox, an argument of the wrong kind or a missing or extra one is
refused in words, and every tool requires exactly the arguments it takes. The timer's task is now
a `Runnable`, which returns no value to mutate. Pitest then killed all 51 mutants. The tests in
`SmsSendTest` were written after the code, so they were not seen red; the mutation run shows that
each of them catches something.

The quality ratchet then found that the plugin's Android part, which no test ran, lowered the
app's line coverage. So the phone's own outbox, with its clock, its timer thread and its
unguessable tokens, moved into the rules as `Outbox.onThePhone`, with a test, and the plugin
class has a plain unit test of its own (`SmsSendPluginTest`). What is left untested is only
what needs Android: the permission check and the default SIM. These tests, too, came after the
code.

Drafts live in memory only, so a text held when the app dies is never sent later. Texts go from
the default SIM, a long one in parts.

Parked for choice A, not built here: a 15-minute expiry for a draft, one draft per recipient with
the replacement announced, an allowlist of Norwegian numbers, and the sent or stopped outcome as a
report or an event to the hub.
