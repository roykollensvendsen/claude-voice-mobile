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
