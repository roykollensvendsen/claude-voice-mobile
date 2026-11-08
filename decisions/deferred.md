# What is deliberately not done yet

A thing left undone with no trigger is a thing forgotten. Each row names what
would make it worth doing, so the question comes back on its own rather than
depending on someone remembering it. Nothing enforces a trigger; the list is
read whenever a decision record is written and when a phase ends.

| Not done | The trigger | Why not now |
|---|---|---|
| A coverage threshold in CI | Two months of measured coverage to set it below | A number picked before there is data is a guess with a gate on it |
| A code of conduct and issue templates | A second contributor, or the first outside issue | Boilerplate answering questions nobody has asked |
| A release workflow and publishing | The first release someone outside will install | A published name is claimed and a published version cannot be reused |
| `CODEOWNERS` | A second person who reviews | It would name one person as the owner of everything |
| `pre-commit` as a requirement rather than an option | A second contributor | The gates run in CI, and a hook one person installs is a hook one person maintains |
| The Android gates in CI (Gradle build, Kotlin lint, unit and instrumented tests) | The first Kotlin code, the audio-focus prototype | A gate over no code checks nothing, and would claim a standard the repository does not yet hold |
| The layer rules in CI | The first Gradle module besides the app | There are no modules for a rule to be about |
| The role skills, `mobile-architecture` and `mobile-android` | The session that owns each one is started | A skill is written by the role that uses it, from what it has learned |
