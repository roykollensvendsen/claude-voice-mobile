# The layer check knows capabilities

The red run is `red-capability-layer.txt`: the layer rules put a capability in no layer, and
they named only the plugin interface as what a plugin may use.

ADR-CVM-007 adds a kind of module below the plugins: a capability under
`capabilities/<name>`, which does one thing on the phone and knows nothing of the hub, and its
adapters under `capabilities/<name>/<adapter>`. The CI layer check (`checkLayers`) now holds its
rules:
- the core never depends on a plugin or a capability;
- a plugin depends on the plugin interface and on capabilities, never on an adapter, which the
  app wires;
- a capability depends on no module of ours;
- an adapter depends only on the capability it adapts;
- no plugin depends on another, as before.

No capability module exists yet, so the tests use a fixture: a capability, an adapter of it, and
a plugin that uses it. The architect asked for that rather than an empty module. The first real
one is `capabilities/smsstore`, for reading text messages (ADR-CVM-010).
