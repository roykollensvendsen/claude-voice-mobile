# The person building the app picks its plugins

The red runs are in `red-pick-modules.txt`: `ModulePicks` did not exist, and the app did not
check a plugin's name against its folder.

ADR-CVM-007 has each install take only the modules it needs. Until now the app built
`plugins/status` by name, in `settings.gradle.kts` and in `App.kt`. Now:

- One Gradle property picks the plugins: `-PcvPlugins=status,sms`. With none given, the build
  takes `status` alone. Anyone's own list goes in their own `~/.gradle/gradle.properties`,
  outside the repository, so nothing here names one person's list.
- A name that is not a folder under `plugins/` fails the build and lists the names it knows.
  An empty list fails too.
- The app's list of plugins, `BUILT_PLUGINS`, is generated from the property. So no code names
  a plugin left out of the build, and a plugin whose entry point is missing fails the compile.
- Each plugin module has one entry point, `org.claudevoice.mobile.plugins.<name>.Entry.plugin
  (context)`, its only Android-facing line. claude-voice-mobile-architect agreed this shape.
- A plugin's folder, package and protocol name are one string. When the app starts, a plugin
  that calls itself anything else is refused.
- The code-health measures take only the plugins the build took.

Tried by hand: `./gradlew projects` includes `:plugins:status` alone, and
`-PcvPlugins=status,smss` fails with "cvPlugins names smss, which is not a module; the modules
are: status".

Not here yet: capabilities a plugin uses are included when the first one, `smsstore`, lands.
