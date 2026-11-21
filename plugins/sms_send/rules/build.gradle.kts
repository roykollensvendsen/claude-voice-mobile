// The sending plugin's decisions, in plain Kotlin, tested off the phone and mutated like the core
// (ADR-CVM-007). It depends on no module of ours; only :plugins:sms_send depends on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

// Mutation testing, for the code-health page only: run by scripts/levels.py, not by `check`.
// The settings are the core's; see core/build.gradle.kts for why each is set.
pitest {
    pitestVersion = libs.versions.pitest.engine.get()
    junit5PluginVersion = libs.versions.pitest.junit5.get()
    targetClasses = listOf("org.claudevoice.mobile.plugins.sms_send.rules.*")
    outputFormats = listOf("XML", "HTML")
    threads = Runtime.getRuntime().availableProcessors()
    avoidCallsTo = setOf("kotlin.jvm.internal")
    timestampedReports = false
}
