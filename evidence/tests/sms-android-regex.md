# A text message no longer crashes the receiver on a phone

The red runs are in `red-sms-android-regex.txt`.

## What the first run on a phone found

The first run of the text-message receiver on an emulator (issue #145) crashed, and the
message was lost. The emulator ran Android 15. The message was made up, from +47 00 00 00 21.
The receiver woke up, even though the app had never been opened. But the class that cleans
the sender's name failed to load. Its patterns used a flag that gives `\s` all of Unicode.
The JVM accepts that flag, so every test off the phone passed. Android's regex engine (ICU)
refuses it and throws when the class loads. The error is not a `RuntimeException`, so the
receiver's backstop did not catch it, and the app's process died.

## The fix

The white-space set and the hidden-character set are now written out character by character,
in `CharacterSets`. Both engines read them the same way. Three patterns use them: two in the
sender cleaning, and one in the one-time-code hiding.

- `CharacterSetsTest` compares each set with what the flag meant on the JVM, for every
  character in Unicode, and expects no difference. A third test checks that neither set leans
  on a flag or a set operation.
- `tests/test_android_regex.py` refuses the flag anywhere in the phone's code from now on. A
  second test checks that the check finds the flag in its forms.

## The run on the emulator after the fix

The same emulator ran a build of this branch. The app had both permissions and had never been
opened. A made-up message was sent, from +47 00 00 00 22.

- The receiver saved it. The queue file appeared in the no-backup folder, readable only by the
  app.
- Neither the text nor the number can be found in the file: it is sealed.
- The app did not crash, and it logged no failed save.
- The default text app still has both messages.

Not shown yet: the hub's `sms_since` call returning the message, and the refusal after
`READ_SMS` is taken back. The phone's server answers only on its Tailscale address, after
pairing, and the emulator is on neither. Those two steps stay open in #145.
