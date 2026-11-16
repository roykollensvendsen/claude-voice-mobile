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

val kotlinSources = measured.map { file(it.removePrefix(":").replace(':', '/') + "/src/main/kotlin") }

// The gate: detekt's default rules fail the build, except the findings in the baseline, which
// the code had when detekt joined the gate. scripts/ratchet.py refuses a new entry in it.
detekt {
    source.setFrom(kotlinSources)
    buildUponDefaultConfig = true
    config.setFrom("config/detekt.yml")
    baseline = file("config/detekt-baseline.xml")
}

// The measure: the same rules with no baseline, plus every function's complexity, for the
// code-health page. It never fails; scripts/levels.py reads its report.
tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektMeasure") {
    description = "Measures detekt's findings and every function's complexity, for the code-health page."
    setSource(kotlinSources)
    config.setFrom("config/detekt-measure.yml")
    buildUponDefaultConfig = true
    ignoreFailures = true
    reports {
        xml.required = true
        xml.outputLocation = layout.buildDirectory.file("reports/detekt/measure.xml")
        html.required = false
        txt.required = false
        sarif.required = false
        md.required = false
    }
}

// `check` here is the whole gate: the layer rules, the check's own tests, and every module's.
tasks.register("check") {
    group = "verification"
    description = "Runs the layer rules, their tests and every module's checks."
    dependsOn("checkLayers", "detekt", gradle.includedBuild("build-logic").task(":test"))
    // :plugins only groups the plugins, and has no check of its own.
    dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName("check") } })
}
