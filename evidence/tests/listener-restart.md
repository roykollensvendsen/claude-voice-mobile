# The listener comes back by itself after Android stops the app

The red run is in `red-listener-restart.txt`.

On a real phone, the owner took the SMS permission away during the test in #145. Android
killed the app ("permissions revoked"), and the hub heard "did not answer" until the owner
opened the app again. Android does this on every revoke. The listener was not sticky, and the
app had no receiver for a restart or an update, so nothing brought it back (#152).

## What the tests pin, off the phone

The one decision is in the core, in `ComeBack`, and `ComeBackTest` covers it:

- A phone with a confirmed name, paired with a hub, starts its listener by itself.
- A phone that was never paired starts nothing.
- A phone whose kept pairing cannot be read starts nothing.
- A phone with no confirmed name starts nothing, whatever pairing is kept.

Pitest on the core killed every mutant in `ComeBack`.

## What the emulator showed

The emulator ran Android 15, with a build of this branch that held the `status` and `sms`
plugins. It has no tailnet, so it cannot pair. A made-up name and pairing record were written
into the app's own no-backup folder: a hub at 100.64.0.1 with a key hash of counting bytes. The
app was opened once, as an owner does, because Android sends nothing to an app that has never
been opened.

1. **The phone restarts.** About 50 seconds after boot, once the emulator had handed out
   `BOOT_COMPLETED`, the app's process started for the broadcast. The listener ran in the
   foreground with the special-use type, with no tap.
2. **The app is updated.** `adb install -r` stopped the app. Three seconds later its process
   started for `MY_PACKAGE_REPLACED`, and the listener was back in the foreground.
3. **A permission is taken away.** Revoking `READ_SMS` killed the app ("permissions revoked").
   Android logged "Scheduling restart of crashed service … in 1000ms", restarted the process a
   second later, and the listener was back in the foreground.

## On the owner's phone

On the owner's Android 16 phone, a local build with only the sticky change behaved the same as
case 3. The revoke killed the app, it restarted a second later in the foreground, and the hub's
next call was answered with `permission_missing`. The restart and update cases have not run on
his phone yet.
