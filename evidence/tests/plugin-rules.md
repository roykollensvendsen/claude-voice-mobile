# A plugin's decisions in a rules module of its own

The red runs are in `red-plugin-rules-layers.txt`, `red-plugin-rules-picks.txt` and
`red-plugin-rules-levels.txt`.

A plugin is an Android library, and Pitest cannot run Android unit tests. So a plugin's
decisions, such as the text-message plugin's filter for one-time codes, could not get a mutation
score. ADR-CVM-007 now lets a plugin keep them in `plugins/<name>/rules`, in plain Kotlin:

- The layer check knows the new layer. A plugin may depend on its own rules. It may not depend on
  another plugin's. The rules depend on no module of ours. The core and the app never depend on
  them. Any other module inside a plugin is in no layer.
- Picking a plugin includes its rules module when the folder has a build file.
- The code-health measure finds plugins and rules modules by walking `plugins/`, builds with
  every plugin picked, and has Pitest mutate each rules module.

No rules module exists yet. For one run, a small one was added to `status`, with one function
and its test, and `status` depending on it. Then it was removed. In that run:

- `projects` listed `:plugins:status:rules` under `:plugins:status`, with the default pick;
- `check` passed;
- `:plugins:status:rules:pitest` made 3 mutants and caught 2;
- `scripts/levels.py` ran in full, and listed `plugins/status/rules` as a part of its own in
  `mutants_by_module`, with its one survivor in `survivors`;
- with the app depending on it, `checkLayers` failed: ":app depends on :plugins:status:rules;
  the app wires a plugin's entry, never its rules".
