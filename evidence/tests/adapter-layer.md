# A plugin reaching for an adapter is told why

The red runs are in `red-adapter-layer.txt`.

ADR-CVM-007 keeps adapters, the code for one make of device behind a capability, out of the
plugins: the app picks and wires them. A plugin that depended on an adapter was already refused,
but with the general message "a plugin depends only on :plugin-api and capabilities". That
message did not say what to do instead. Now it reads "a plugin never depends on an adapter; the
app wires adapters".

Two rules that already held had no test of their own. Each now has one: the core never depends on
an adapter, and an adapter depends on neither the plugin interface nor the core. Both tests were
seen red with their rule switched off for one run.
