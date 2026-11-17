# agent_info: what the phone is, offers and allows

The red run, with stubs in place so the tests compiled, is `red-agent-info.txt`: 12 tests failed.

- **The answer, field by field, against protocol v1's `agent-info.json`.** It holds exactly
  `protocol` (1), `machine`, `address`, `hub` (null before pairing), `tools` (every plugin's,
  without `agent_info`), `version`, `plugins` and `privacy`, in that order (`AgentInfoTest`).
- **Read when the hub asks.** The hub's name, a changed privacy setting, and what a plugin
  uses are read at the time of the call, so a change on the phone shows at once.
- **Each plugin's entry** (`plugin.json`) has `name`, `tools`, `confirms` and `uses`.
- **The registry refuses** a plugin offering a tool named `agent_info` (plugin `agent` plus
  tool `info` would take it), a `confirms` entry that is not one of the plugin's own tools or
  appears twice, and a `uses` name that breaks the protocol's rule or appears twice.
- **In MCP,** `agent_info` is listed first with input `{"type":"object"}`, and a call is
  answered from the core without reaching a plugin and without keeping a call id, since it
  only reads.

Two designs were dropped on the way, both because of Pitest:

- `agent_info` as a small plugin object inside the handler left four mutants with no
  coverage, from fields nothing reads. It is now one branch.
- `uses.all { ... }` left a mutant no test can catch: the inlined `all` returns early for an
  empty list, and negating that gives the same answer. `filterNot` has no such shortcut.

Pitest on `:core` caught all but main's two old mutants, in `Tailnet.kt` and `KeptGate.kt`.
`python3 scripts/ratchet.py check --base origin/main` holds.
