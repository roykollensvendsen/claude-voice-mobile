// The text-message plugin's decisions, in plain Kotlin, tested off the phone and mutated like
// the core (ADR-CVM-007). It depends on no module of ours; only :plugins:sms depends on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
}

kotlin { jvmToolchain(17) }

dependencies {
    // The sms_since answer is JSON, built here so that it is tested and mutated with the rest.
    implementation(libs.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // How many listed texts the masking hid, kept with the CI run, never committed (ADR-CVM-010).
    // Only this task sets it, so Pitest's runs of the same tests leave the count alone.
    val report = layout.buildDirectory.file("reports/otp-masking/listed.txt")
    outputs.file(report)
    systemProperty("otpListedReport", report.get().asFile.path)
}

// Mutation testing, for the code-health page only: run by scripts/levels.py, not by `check`.
// The settings are the core's; see core/build.gradle.kts for why each is set.
pitest {
    pitestVersion = libs.versions.pitest.engine.get()
    junit5PluginVersion = libs.versions.pitest.junit5.get()
    targetClasses = listOf("org.claudevoice.mobile.plugins.sms.rules.*")
    outputFormats = listOf("XML", "HTML")
    threads = Runtime.getRuntime().availableProcessors()
    avoidCallsTo = setOf("kotlin.jvm.internal")
    timestampedReports = false
}
