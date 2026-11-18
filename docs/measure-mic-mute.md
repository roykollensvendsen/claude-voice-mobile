# Measurements: a microphone mute that holds

The results of [ADR-CVM-008](../decisions/ADR-CVM-008-a-microphone-mute-that-holds.md)'s
measurements, from the test app in
[`prototype/mic-mute/`](../prototype/mic-mute/README.md). Only pass or fail is
recorded here, with the date and the phone. The logs, numbers, recordings and
transcripts stay outside the repository, as the record says.

Mute (A) is `setMicrophoneMute` from the test app. Mute (B) is the owner's own
switch for microphone access.

| Measurement | Path | Mute | Date | Phone | Result |
|---|---|---|---|---|---|
| M1a, M1b, M1c | built-in microphone | A | — | phone-a | not measured yet |
| M1a, M1b, M1c | built-in microphone | B | — | phone-a | not measured yet |
| M1a, M1b, M1c | earbuds over SCO | A | — | phone-a | not measured yet |
| M1a, M1b, M1c | earbuds over SCO | B | — | phone-a | not measured yet |
| M1a, M1b, M1c | earbuds over LE Audio, if used | A, B | — | phone-a | not measured yet |
| M2, earbud tap | — | — | — | phone-a | not measured yet |
| M2, plain headset | — | — | — | phone-a | not measured yet |
| M3, 30 s and 5 min | — | A | — | phone-a | not measured yet |
| M4, 95th percentile under 300 ms | — | A | — | phone-a | not measured yet |
| M5, 10 minutes | — | A | — | phone-a | not measured yet |
| M6, tones heard | — | — | — | phone-a | not measured yet |
