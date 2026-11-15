# ADR-CVM-007: Modules each install picks, and capabilities below the plugins

## Status

Proposed 2026-11-15 by the architect session, at the owner's request, relayed
by the manager session. Settled when the owner merges it. It amends the layer
rules of [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md) and keeps
everything else in that record.

## Context

- The owner wants the app modular, so that each person who installs it takes
  only the modules they need: a microphone mute with their own headset, calls,
  location, notifications, media and so on.
- ADR-CVM-003 already splits the app into `core`, `plugin-api` and one module
  per plugin, with layer rules checked in CI: the core never depends on a
  plugin, a plugin depends only on `plugin-api`, and no plugin depends on
  another.
- The microphone mute (ADR-CVM-008) is needed by more than one caller: a tap on
  the earbuds, the hub's tools, and later the `call` plugin. Under "no plugin
  depends on another", it cannot live in one plugin and be used by another.
- A module that is left out should cost nothing: no permission in the
  manifest, no prompt, no code on the phone. A module that is built in but
  switched off should ask for nothing.

## Options considered

- **Build flavours, one per mix of modules.** The number of flavours doubles with
  every module. Not taken.
- **Every module in every build, switched on and off at run time.** That is
  simple, but every module's permissions are in the manifest whether used or
  not, and the owner of a phone sees permissions for things they never use. Not
  taken on its own; kept as the second step below.
- **Picking modules when the app is built, and switching them at run time
  too.** **Taken.**
- **For the mute:** one plugin owning the headset adapters, which leaves the
  `call` plugin unable to use it; or **a capability module below the plugins**,
  which any plugin may use. **The capability is taken.**

## Decision

**A fourth kind of module: capabilities, under `capabilities/<name>`.** A
capability does one thing on the phone, such as muting the microphone, and
knows nothing of the hub. The layer rules become:

- the core never depends on a plugin or a capability;
- a plugin depends on `plugin-api` and on capabilities, and on nothing else;
- a capability depends on no module of ours. Its adapters, such as one for a
  make of headset, are modules of their own, and each depends only on the
  capability it adapts;
- no plugin depends on another plugin;
- `app` is the only module that sees them all, to wire them together.

The CI check that enforces the layer rules learns the new kind in the same pull
request that adds the first capability.

**A capability is reusable outside this app.** It has a package of its own, uses
no type from `core`, `plugin-api` or the protocol, and has its own tests. It
stays a module in this repository until a second project wants it. Moving it to
a repository of its own, and publishing it, is the owner's decision, and is a
row in [`deferred.md`](deferred.md).

**Modules are picked when the app is built.** One Gradle property lists the
plugins, for example `-PcvPlugins=status,micmute,mobiledata`. Gradle includes
only those modules and the capabilities they use. The app's list of plugins is
generated from the property, so no code names a plugin that was left out. A name
in the property that is not a module fails the build, with the list of names it
knows. Each module brings its own permissions and foreground service types in
its own manifest. Android merges every included module's manifest into the
app's, libraries' entries too, so the guarantee is only for a module left out
of the build. **A CI job checks it:** it reads the app's merged manifest and
fails when a permission or service type in it is not declared by a module in
the property's list, or by the app itself. It runs twice: on the default list, and
on a list of every module, so that a module's entries are checked even when
the default leaves it out. The
default list in the repository is `status` alone. Anyone's own list, the owner's
included, goes in their own `~/.gradle/gradle.properties`, outside the
repository, so nothing in it is tied to one phone, headset or person.

**And switched at run time.** Each plugin that is built in has an on and off
switch in the app's settings, off when first installed. A runtime permission,
one the person must allow, is asked for only when its plugin is switched on.
The switch does not withdraw what Android grants at install: permissions such
as `MODIFY_AUDIO_SETTINGS` and the foreground service types of a built-in
module are held whatever the switch says. Only leaving the module out of the
build removes those. The hub sees only the plugins
that are on. A test proves that a plugin switched off is never registered: it is
not in `tools/list`, not in `agent_info`, and a call to its tool is refused as
unknown. The plugin list on the hub changes when a plugin is switched on, so the
owner approves it, as protocol version 1 already requires.

**A third party adds a module without changing the core.** They write a Gradle
module that implements `plugin-api`, add it to the build and name it in the
property. No core file changes. Loading plugins at run time from other apps is
not done: it would need a trust model of its own. That is a row in
`deferred.md` too.

**The layer rules are not a security boundary.** They keep the code in order,
not a module out of what the app holds. A module built into the app runs in the
app's process, holds every permission the app holds, and could read the pairing
key. So a third party's module is trusted as much as the core. It is built in
only by someone who has read it, and it is approved like any other plugin.

## Consequences

- A person who wants only the mute builds an app that holds the mute and
  nothing else. Its manifest has no location or contacts permission at all.
- The layer check gains a rule and a named test for each edge: a plugin may use
  a capability; a capability may not use a plugin, `plugin-api`, the core or
  another capability; an adapter may not use a plugin or another adapter; and
  the core may use neither a plugin nor a capability.
- Publishing `plugin-api` or a capability is still not done. Until it is, a third
  party builds from this repository.

## Related

- [ADR-CVM-003](ADR-CVM-003-the-apps-internal-design.md): the core, the plugins
  and the layer rules this record amends.
- [ADR-CVM-008](ADR-CVM-008-a-microphone-mute-that-holds.md): the microphone
  mute, the first capability.
