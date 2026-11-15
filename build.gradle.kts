plugins {
    id("claudevoice.layers")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

// The measures the code-health page shows (scripts/levels.py reads their reports). They
// measure only; the gate below does not depend on them.
val measured = listOf(":core", ":plugin-api", ":app", ":plugins:status")
subprojects {
    if (path in measured) apply(plugin = "org.jetbrains.kotlinx.kover")
}
dependencies { measured.forEach { kover(project(it)) } }

detekt {
    source.setFrom(measured.map { file(it.removePrefix(":").replace(':', '/') + "/src/main/kotlin") })
    config.setFrom("config/detekt.yml")
    buildUponDefaultConfig = true
    ignoreFailures = true
}

// `check` here is the whole gate: the layer rules, the check's own tests, and every module's.
tasks.register("check") {
    group = "verification"
    description = "Runs the layer rules, their tests and every module's checks."
    dependsOn("checkLayers", gradle.includedBuild("build-logic").task(":test"))
    // :plugins only groups the plugins, and has no check of its own.
    dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName("check") } })
}
