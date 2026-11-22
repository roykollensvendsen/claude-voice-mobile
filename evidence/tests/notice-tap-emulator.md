# The hidden-notice line on an emulator

Run on 2026-11-22 on an Android 15 emulator (API 35, Norwegian system language), with a debug build of this branch (plugins status, sms, sms_send). The steps drive the screen as the owner would, through Android's own UI dump and taps (`uiautomator dump`, `input tap`). Each result below is what the dump showed after the step.

The emulator's own launcher showed "not responding" dialogs a few times because the machine was under load. Each one was closed before the next step; none came from this app.

| Step | What the owner does | What the screen showed |
|------|---------------------|------------------------|
| 1 | Clears the app's data and starts it | The first-start request: "Vil du la claude-voice sende deg varsler?" |
| 2 | Taps "Ikke tillat" | The main screen with the name step. No line, since no listener runs yet. |
| 3 | Confirms the phone's name, leaves the app and comes back | The line: "Lytteren kjører, men Android skjuler varselet. Trykk for å vise det." |
| 4 | Taps the line | The request again, shown by Android's permission screen (the tap asks first). |
| 5 | Taps "Ikke tillat" | The main screen, line still there. The request was shown, so the next tap asks again. |
| 6 | Taps the line | The app's notification settings: "Du har ikke tillatt varsler fra denne appen". Android shows no third request, so the refusal comes at once with no pause, and the line opens the settings. |
| 7 | Turns on "claude-voice: alle varsler" and goes back | The main screen without the line. `dumpsys package` lists POST_NOTIFICATIONS as granted. |

After step 1, the app's ask record (`no_backup/asked-permissions`) held `android.permission.POST_NOTIFICATIONS`. That record is what lets the access the hub reads say "refused" rather than "never_asked" (ADR-CVM-011).

What this covers that the unit tests can't: `onResume` showing and hiding the line, `onPause` and window focus marking a request as shown, the permission result reaching the line, and the settings page opening.

## Does an automatic refusal pause the screen? (f1 on #165)

Run on 2026-11-22 on the same Android 15 emulator, with a debug build of main after #175. Step 6 above can't tell whether the settings opened because Android refused with no pause, or because of the backstop (a second refusal in a row). This run starts the line's count again before the deciding tap, so only the pause signal can open the settings.

| Step | What the owner does | What the screen showed |
|------|---------------------|------------------------|
| 1 | Clears the app's data and starts it | The first-start request |
| 2 | Taps "Ikke tillat", confirms the name, leaves the app and comes back | The line |
| 3 | Taps the line, then "Ikke tillat" | The main screen with the line. `dumpsys package` now marks POST_NOTIFICATIONS `USER_FIXED`: Android won't show the request again. |
| 4 | Force-stops the app and opens it again | The line. The line's count of refusals starts at 0 again. |
| 5 | Taps the line once | **The main screen, unchanged.** Nothing opened. |
| 6 | Taps the line again | The app's notification settings: "Du har ikke tillatt varsler fra denne appen" |

**What it shows:** even when Android refuses at once, it starts its permission screen (`GrantPermissionsActivity`, a see-through window; the system log records `START … REQUEST_PERMISSIONS`). The app loses the top place, so the line counts the request as shown. The first tap after a fresh start is dead, and the backstop opens the settings on the second. ADR-CVM-003's claim that an automatic refusal comes with no pause does not hold on Android 15. The owner is never stuck, but it costs one tap.

A turn of the phone also pauses the screen. So a request Android never showed counts as shown if the phone is turned meanwhile, which costs one more tap. The same backstop bounds it.

Not run: Android 13 and 14. This machine has only the Android 11 and 15 system images.
