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
    outputFormats = listOf("XML")
    // Data-only classes, whose every mutant is in code the compiler wrote; the list is
    // guarded by tests/test_pitest_data_only.py.
    excludedClasses = rootProject.file("config/pitest-data-only.txt").readLines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    timestampedReports = false
}
