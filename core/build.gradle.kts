// Pairing, the protocol version, approvals and the log of every call, in plain Kotlin.
// It holds no phone function: those are plugins, and the core may not depend on one.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":plugin-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

// Mutation testing, for the code-health page only: run by scripts/levels.py, not by `check`.
pitest {
    pitestVersion = libs.versions.pitest.engine.get()
    junit5PluginVersion = libs.versions.pitest.junit5.get()
    targetClasses = listOf("org.claudevoice.mobile.core.*")
    // XML for scripts/levels.py; HTML so a survivor can be read in the run's artifact.
    outputFormats = listOf("XML", "HTML")
    // Every run measures in full, with no history of earlier results: Pitest's history can
    // keep a mutant counted as caught after a change it does not notice, and this run gates.
    threads = Runtime.getRuntime().availableProcessors()
    // The null checks the Kotlin compiler inserts are not code anyone wrote.
    avoidCallsTo = setOf("kotlin.jvm.internal")
    // Data-only classes, whose every mutant is in code the compiler wrote; the list is
    // guarded by tests/test_pitest_data_only.py.
    excludedClasses = rootProject.file("config/pitest-data-only.txt").readLines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    timestampedReports = false
}
