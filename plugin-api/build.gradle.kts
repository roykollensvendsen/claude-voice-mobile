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
    // XML for scripts/levels.py; HTML so a survivor can be read in the run's artifact.
    outputFormats = listOf("XML", "HTML")
    threads = Runtime.getRuntime().availableProcessors()
    // Earlier results, so a run mutates again only what changed. CI keeps the file in its
    // cache, keyed on main; it is never committed.
    historyInputLocation = rootProject.layout.buildDirectory.file("pitest-history/plugin-api.bin")
    historyOutputLocation = rootProject.layout.buildDirectory.file("pitest-history/plugin-api.bin")
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
