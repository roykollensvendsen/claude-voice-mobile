import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** checkLayers in a real build: it reads the dependencies the modules declare, and fails the build. */
class CheckLayersTest {
    @TempDir
    lateinit var dir: File

    private fun build(statusDependsOn: String): GradleRunner {
        dir.resolve("settings.gradle.kts").writeText(
            """include(":core", ":plugin-api", ":plugins:status", ":plugins:location")""",
        )
        dir.resolve("build.gradle.kts").writeText("""plugins { id("claudevoice.layers") }""")
        val modules = mapOf(
            "core" to """implementation(project(":plugin-api"))""",
            "plugin-api" to "",
            "plugins/location" to """implementation(project(":plugin-api"))""",
            "plugins/status" to """implementation(project("$statusDependsOn"))""",
        )
        for ((path, dependency) in modules) {
            dir.resolve(path).mkdirs()
            dir.resolve("$path/build.gradle.kts").writeText(
                "plugins { `java-library` }\ndependencies { $dependency }\n",
            )
        }
        return GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments("checkLayers")
    }

    @Test
    fun `a build that keeps the rules passes the check`() {
        val result = build(":plugin-api").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkLayers")?.outcome)
    }

    @Test
    fun `a plugin that depends on another fails the build`() {
        val result = build(":plugins:location").buildAndFail()
        assertTrue(
            result.output.contains(":plugins:status depends on :plugins:location; no plugin depends on another"),
            result.output,
        )
    }

    @Test
    fun `a plugin that depends on the core fails the build`() {
        val result = build(":core").buildAndFail()
        assertTrue(result.output.contains(":plugins:status depends on :core"), result.output)
    }

    @Test
    fun `a plugin's rules may use a capability that applies no Android plugin`() {
        dir.resolve("settings.gradle.kts").writeText(
            """include(":plugin-api", ":plugins:sms", ":plugins:sms:rules", ":capabilities:cursorqueue")""",
        )
        dir.resolve("build.gradle.kts").writeText("""plugins { id("claudevoice.layers") }""")
        val modules = mapOf(
            "plugin-api" to "",
            "capabilities/cursorqueue" to "",
            "plugins/sms" to """implementation(project(":plugin-api")); implementation(project(":plugins:sms:rules"))""",
            "plugins/sms/rules" to """implementation(project(":capabilities:cursorqueue"))""",
        )
        for ((path, dependency) in modules) {
            dir.resolve(path).mkdirs()
            dir.resolve("$path/build.gradle.kts").writeText(
                "plugins { `java-library` }\ndependencies { $dependency }\n",
            )
        }
        val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments("checkLayers").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkLayers")?.outcome)
    }
}
