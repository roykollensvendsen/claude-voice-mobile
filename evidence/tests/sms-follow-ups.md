# A failed save tells the hub to ask again, and a number written with spaces is a number

The red runs are in `red-sms-follow-ups.txt`. Three tests failed before the change, and the fourth
was seen red with the one line it guards switched off.

The reviews of `sms_since` (#139) left these follow-ups, all in the rules module:

- **A failed save.** The queue is saved before an answer leaves. When that save fails, the hub
  used to get only the core's general refusal, "The phone could not run sms_since". Now it hears
  that nothing was handed out and that it may ask again. The words never carry the error itself.
  The acknowledgement of the cursor passed may already hold in memory. That is allowed, since
  the hub did pass it. Asking again with the same cursor gives the same messages and a cursor
  that works. A change that could not be saved stays marked, so the next call saves it, and after
  a restart an acknowledged message is not given again. The test makes the save fail before
  anything is written, as a full disk would. With the line that marks the change again switched
  off, it failed. That is the second red run.
- **A number written with spaces.** "+47 123 45 678", or with dashes, dots or brackets, is now a
  number, kept without them. This holds only when nothing else is left, so "Bank 24" stays a name.
- **A lookup that gives something else.** The phone's lookup that puts a national number in E.164
  form is believed only when it gives E.164. Otherwise the digits are kept as they came.
- **Hiding is decided on the sender as it came.** A test now pins that "+47 123 45 678" is not a
  named sender there either, so a spaced number never gets a name's stricter hiding by accident.
  That test passed at once, since this was already so.

One review point was weighed and left as it is: the count of held messages at the start of the
queue file stays outside the seal and is not authenticated. It is there so that a lost key can
still count the messages lost. Anyone who could change it could as well delete the file, which
gives a fresh queue and a reset in the same way. For a file that does open, its true contents are
used, never the count. The mobile architect agreed.

Pitest caught every mutant in the rules module.
