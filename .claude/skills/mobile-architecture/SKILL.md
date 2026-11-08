---
name: mobile-architecture
description: >-
  How the claude-voice phone app's architect works - the hub-to-agent protocol
  (written in claude-voice at docs/agent-protocol.md with JSON schemas, versioned,
  frozen only by Roy, never edited once frozen) and the app's layer rules (core never
  uses a plugin, a plugin uses only plugin-api, no plugin uses another, checked by
  ./gradlew checkLayers). Use whenever the work touches the agent protocol, its
  schemas or version, a new protocol version, the plugin format, the privacy rules on
  the wire, a new Gradle module in claude-voice-mobile, LayerRules.kt, or which
  module may depend on which - even if the request just says "add a plugin module",
  "change what the phone sends the hub" or "is this field allowed".
---

# Mobile architecture

The architect owns two things: the protocol the hub and every agent speak, and the
rules that keep the app's modules apart. Both are decided in records first:
ADR-CV-006 and ADR-CV-007 in claude-voice, ADR-CVM-003 here.

## The protocol

It lives in claude-voice, never here: `docs/agent-protocol.md`, schemas in
`docs/agent-protocol/v<N>/`, frozen versions in `docs/agent-protocol/frozen.json`.
The worktree for it is a separate checkout of claude-voice on a `protocol/` branch.

1. **Describe the code before adding to it.** Read `agent.py`, `machines.py`,
   `plugins.py` and `tailnet.py` in claude-voice first. What they speak is version
   0; a new version says which parts are new. A spec that disagrees with the code
   the hub runs is worse than none.
2. **Test the schemas against the real agent**, not only against their own
   examples: `tests/test_agent_protocol.py` posts to the agent's own gate and
   validates its answers. Write the test before the schema and watch it fail.
3. **A rule between two fields goes in the schema** (`if`/`then`), with a test that
   an example breaking it is refused. Rules that need context the schema cannot see
   (which plugin asked) go in the text as a hub or agent obligation.
4. **Write the switch-over for the version before.** A peer that sends no version
   is version 0 and is accepted until Roy decides otherwise. Freezing a version and
   refusing the old one are two separate needs-roy items, so freezing cuts no
   machine off.
5. **claude-voice-work reviews; it merges.** Its commit hook refuses a
   `Claude-Session:` line, so leave that out in that repository. Do not merge a
   claude-voice pull request yourself, even when its owner could not: tell Roy.
6. **Freezing is Roy's.** Open a needs-roy issue in claude-voice-mobile, in plain
   Norwegian, and add it to STATUS.md. On his yes, record each file's SHA-256 under
   its version in `frozen.json`; the test then holds the bytes. Never edit a frozen
   file; copy the folder to `v<N+1>/` and change the copy.
7. **Adding tests in claude-voice moves its quality ratchet.** Record the new test
   count in `quality.json` (`scripts/ratchet.py update`, about ten minutes, or set
   `tests` by hand for a test-only change), and run
   `ratchet.py check --base origin/main --skip-mutation` before pushing.

## The layer rules

`build-logic/src/main/kotlin/LayerRules.kt`, run as `./gradlew checkLayers`, part
of `./gradlew check` and the CI job `android`.

- A new module is refused until it has a layer in `LayerRules.layerOf`. Adding one
  is a change to the rules: a test in `LayerRulesTest.kt` first.
- Test dependencies count. A plugin's test may not use the core either.
- `plugin-api` cannot see `core`, since `core` depends on it. A type a plugin must
  read but never write (the settings only the phone changes) is a read-only
  interface in `plugin-api`, with the writer in `core`.
- Packages are `org.claudevoice.mobile.*`. No personal name goes into a package,
  an application id or anything else that ends up in logs.

## Building here

- JDK 17: the default java on this machine is 1.8, so set
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`.
- The Android SDK is at `~/Android/Sdk`; put `sdk.dir` in an untracked
  `local.properties`.
- GitHub's runners sometimes time out downloading Gradle or the commit linter.
  Rerun the failed jobs before looking for a cause in the change.
- Agree a module layout with claude-voice-mobile-android before building it; they
  own the code inside the modules.
