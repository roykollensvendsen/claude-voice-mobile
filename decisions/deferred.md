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
| Kotlin style lint and instrumented tests in CI (the build, Android lint, unit tests and the layer rules already run) | The first module with code worth styling, and the first test that needs a device or an emulator | A style gate over a skeleton checks nothing, and an emulator in CI costs minutes on every pull request |
| A check that no plugin and no tool the hub can call gets a writer for the settings only the phone changes | The settings type is added to `plugin-api`, in its own pull request | There is no such type yet for a check to be about |
| The role skill `mobile-android` (`mobile-architecture` is written) | The Android developer has learned something worth keeping | A skill is written by the role that uses it, from what it has learned |
