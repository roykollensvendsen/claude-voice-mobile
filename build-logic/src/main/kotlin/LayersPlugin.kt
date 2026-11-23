import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Applied to the root build: adds `checkLayers`, which fails on any edge LayerRules refuses. */
class LayersPlugin : Plugin<Project> {
    override fun apply(root: Project) {
        root.tasks.register("checkLayers", CheckLayers::class.java) {
            group = "verification"
            description = "Fails when a module depends on one its layer may not reach."
            // Read once every module has been configured, so each one's dependencies are there.
            edges.set(root.provider { edges(root) })
            pure.set(root.provider { pure(root) })
        }
    }

    // A capability that applies no Android plugin can reach nothing on the phone: it holds only
    // pure logic, which a plugin's rules may use (ADR-CVM-007).
    private fun pure(root: Project): Set<String> =
        root.subprojects
            .filter { it.path.startsWith(":capabilities:") }
            .filterNot { module -> ANDROID.any { module.plugins.hasPlugin(it) } }
            .map { it.path }
            .toSet()

    private fun edges(root: Project): Map<String, List<String>> =
        root.subprojects.associate { module ->
            module.path to
                module.configurations
                    .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
                    .map { it.path }
                    .filter { it != module.path }
                    .distinct()
                    .sorted()
        }
}

private val ANDROID = listOf("com.android.library", "com.android.application")

abstract class CheckLayers : DefaultTask() {
    @get:Input
    abstract val edges: MapProperty<String, List<String>>

    @get:Input
    abstract val pure: SetProperty<String>

    @TaskAction
    fun check() {
        val broken = LayerRules.violations(edges.get(), pure.get())
        if (broken.isNotEmpty()) {
            throw GradleException("The layer rules of ADR-CVM-003 and ADR-CVM-007 are broken:\n" + broken.joinToString("\n") { "  $it" })
        }
    }
}
