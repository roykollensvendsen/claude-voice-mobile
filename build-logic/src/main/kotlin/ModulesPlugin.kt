import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Applied to the settings: includes the plugins `cvPlugins` picks (ADR-CVM-007), and keeps the
 * list where the builds can read it, as the extra property `cvPlugins`. A plugin is a folder under
 * `plugins/` with a build file of its own; its rules module, `plugins/<name>/rules`, comes with it.
 */
class ModulesPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val folder = settings.rootDir.resolve("plugins")
        val known = folder.listFiles().orEmpty().filter { it.resolve("build.gradle.kts").isFile }.map { it.name }.toSet()
        val given = settings.providers.gradleProperty(ModulePicks.PROPERTY).orNull
        val picked = runCatching { ModulePicks.picked(given, known) }
            .getOrElse { throw org.gradle.api.GradleException(it.message ?: "", it) }
        val hasRules = { name: String -> folder.resolve("$name/rules/build.gradle.kts").isFile }
        ModulePicks.included(picked, hasRules).forEach { settings.include(it) }
        settings.gradle.extensions.extraProperties.set(ModulePicks.PROPERTY, picked)
    }
}
