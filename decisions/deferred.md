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
| Instrumented tests in CI (the build, detekt, Android lint, unit tests and the layer rules already run) | The first test that needs a device or an emulator | An emulator in CI costs minutes on every pull request |
| `:app` and the plugins in the `mutants` measure (ADR-CVM-004) | A mutation tool that runs Android unit tests | Pitest runs only plain JVM tests, so it measures `:core` and `:plugin-api` |
| The `mutation` measure: rules proven by a test, as claude-voice counts them (ADR-CVM-004) | The app's first rule rows: a `# RULE:` line in Kotlin with a row in `scripts/mutations.toml`, which needs `scripts/mutate.py` to read `.kt` files | No rule rows exist yet, and Pitest's mutants are another measure, kept under `mutants` |
| Knowing the hub's machine by asking Tailscale, as protocol version 1 says, rather than by the tailnet address pinned at pairing (`CallerCheck`, `PinnedAddress`) | Tailscale on Android offers a lookup of the caller | It offers none today; the pinned address and the paired key are checked together, and a changed address says to pair again |
| The role skill `mobile-android` (`mobile-architecture` is written) | The Android developer has learned something worth keeping | A skill is written by the role that uses it, from what it has learned |
| Coverage-guided fuzzing of the HTTP parser with Jazzer (ADR-CVM-005) | A parser fault found anywhere but in the seeded fuzz test | The seeded fuzz test and the named hostile set cover the framing rules, and Jazzer brings an agent and a native library into the build |
