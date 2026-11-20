# The phone hears each new text message and keeps it for the hub

The red runs are in `red-sms-receiver.txt`. There are three: the refusal that asks the owner to
free space, every new rule behind the receiver, and the manifest.

This is the third of the mobile architect's steps for reading text messages (ADR-CVM-010),
the part called c1. A receiver hears each new message and puts it in the sealed queue that
`sms_since` answers from, and the plugin now offers that tool. What the receiver must do is
listed in ADR-CVM-010 (#142). This file says how each point is tested and links there rather
than repeating it. Turning national numbers into E.164 form is the next part, c2, in the
phone's own message store. Until then a national number is kept as its digits, still a number,
which the answer allows.

What the tests pin, off the phone:

- **Only the system may hand in a message.** `tests/test_sms_manifest.py` checks that the
  receiver for `SMS_RECEIVED` is the only one, and that it is guarded by `BROADCAST_SMS`. It
  also checks that the plugin holds `RECEIVE_SMS` and `READ_SMS` and never `SEND_SMS`. A third
  test shows the check fails on a receiver without the guard.
- **One message from many parts.** A long message comes as several parts from one sender, and
  is kept as one, joined in order. A part from another sender starts another message. A part
  with no sender or no text counts as empty.
- **The phone's own time.** A message is kept at the phone's clock when it arrives, with the
  phone's offset. The parts' own time is the network's and is never read. A test pins the time
  and the offset kept.
- **A failed save is tried again.** The receiver tries the save twice. A test shows one failure
  is saved by the second try. After two failures the message stays in memory and is saved by
  the next change. The one gap this leaves is stated in ADR-CVM-010.
- **One inbox for the whole app.** The receiver and the tool get the same inbox, so they share
  one lock and one file. A test asks for it from two threads at once and gets one, made once.
- **A key the Keystore will not give never stops the app.** The seal asks for its key each time.
  A key the platform no longer accepts is forgotten and a new one made, once. Any other key
  failure fails the seal as a failed save, which the hub hears as "ask again". When opening,
  the same failure is a lost key, and the queue starts again counting what it held as dropped.
- **May the phone read messages?** When the owner has taken back `RECEIVE_SMS` or `READ_SMS`,
  a call is refused with the code `permission_missing`. Nothing is handed out. The words say
  where to allow it again, and to choose "Allow restricted settings" first if the switch is
  greyed out.
- **A full disk.** One failed save says "ask again". More than one in a row says the phone's
  storage may be full and asks the owner to free space. The count starts again after a save that
  works.

What only a phone shows, and is not tried yet: the receiver itself, the Keystore key and the
folder sync are Android code with no test off the phone. They are kept thin. Every choice they
make is in the rules module, which Pitest mutates and which killed every mutant. They were
compiled, linted and checked by detekt. They have not run on a phone or the emulator yet,
because the machine was short of memory. That run comes before the plugin is switched on for
the owner.

Not in this step, and said plainly:

- **There is no switch for the plugin yet.** The plugin is only in a build that names `sms` in
  `-PcvPlugins`, and nothing is kept unless the owner has granted `RECEIVE_SMS`. The switch
  comes with tasks #78 and #58. When it is off, it should disable the receiver itself, so a
  switched-off app never sees a message.
- **Messages before the first unlock.** The app is not direct-boot aware. A message that
  arrives after a restart and before the first unlock never reaches the queue. The default
  text app still has it (ADR-CVM-010).
