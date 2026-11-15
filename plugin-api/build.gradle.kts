// The one interface a plugin implements. Plain Kotlin: it reaches no Android API and no module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.serialization.json)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

// Mutation testing, for the code-health page only: run by scripts/levels.py, not by `check`.
pitest {
    pitestVersion = libs.versions.pitest.engine.get()
    junit5PluginVersion = libs.versions.pitest.junit5.get()
    targetClasses = listOf("org.claudevoice.mobile.pluginapi.*")
    outputFormats = listOf("XML")
    // The null checks the Kotlin compiler inserts are not code anyone wrote.
    avoidCallsTo = setOf("kotlin.jvm.internal")
    // Data-only classes, whose every mutant is in code the compiler wrote; the list is
    // guarded by tests/test_pitest_data_only.py.
    excludedClasses = rootProject.file("config/pitest-data-only.txt").readLines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    timestampedReports = false
    // Every class here is data-only today, so there may be no mutants at all.
    failWhenNoMutations = false
}
