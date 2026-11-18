# The app asks only for what a module it took declares

The red runs are in `red-manifest-check.txt`: `ManifestEntries` and `ManifestCheck` were stubs
that read nothing and refused nothing, and `all` was not a name `ModulePicks` knew.

ADR-CVM-007 promises that a plugin left out of the build adds nothing to the app. Android merges
every included module's manifest into the app's, and every library's too, so a library can add a
permission no module of ours asked for. Now:

- `:app:check<Variant>MergedManifest` reads the merged manifest of each variant and fails on any
  permission (`uses-permission` or `uses-permission-sdk-23`) or foreground service type that no
  manifest in the build declares. The build holds only the app, the fixed modules and the
  plugins `-PcvPlugins` picked. The task is part of `check`.
- `-PcvPlugins=all` takes every folder under `plugins/`. CI runs the gate once on the default
  list and once on `all`, so every plugin's tests run and its manifest entries are checked, and
  a new plugin folder cannot be left out of CI's list.

Tried by hand, in `red-library-permission.txt`: with `androidx.work:work-runtime` added to the
app for the run, the check fails and names `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`,
`WAKE_LOCK` and AndroidX's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Without it, both
variants pass, on the default list and on `all`.

Rebased onto the plugin-name check (#117). `all` now expands to the folders first, so each goes
through the protocol's name check too: a folder such as `Sms-2` fails under `all` as it does
when named. A plugin folder named `all` is refused, since `all` means every plugin. The red run
for both is in `red-all-name-check.txt`.
