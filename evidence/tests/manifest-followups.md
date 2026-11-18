# The permission check reads our manifests as the merge does

The red runs are in `red-manifest-followups.txt` and `red-application-id.txt`.

The reviews of the merged-manifest check (#116) found three gaps. They are closed here:

- **A line that removes a permission declared it.** A module may write
  `tools:node="remove"` to take a library's permission out. The check counted that line as
  declaring the permission, so if the removal ever stopped working, the permission would pass.
  Such a line now declares nothing.
- **The build's own id never matched.** A module names the build's id as the placeholder
  `${applicationId}`, and the merged manifest has it filled in. So a permission such as
  `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` could never be declared.
  AndroidX's core library adds exactly that one, and the notifications the app will need come
  with it. The check now fills in each variant's id before it compares. Tried with
  `androidx.core` added for one run: undeclared, the check names the permission; declared in
  the app's manifest with the placeholder, it passes.
- **The message said where to declare.** Only each module's `src/main` manifest is read, so a
  permission declared for one variant alone fails as well. The failure now says to declare it
  in the `src/main/AndroidManifest.xml` of the module that needs it. ADR-CVM-007 now says "a
  module in the build", as the code does.
