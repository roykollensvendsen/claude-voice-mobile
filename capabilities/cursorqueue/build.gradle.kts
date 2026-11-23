// The ids, cursors, acknowledgement, resend, drops and reset that a plugin's poll gives, in plain
// Kotlin (ADR-CVM-007): a capability that holds only pure logic, which `sms` and `sms_send` both
// use. It reaches nothing on the phone, declares no permission, has no manifest, and depends on no
// module of ours. It is tested off the phone and mutated like the core.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
}

kotlin { jvmToolchain(17) }

dependencies {
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
    targetClasses = listOf("org.claudevoice.mobile.capabilities.cursorqueue.*")
    outputFormats = listOf("XML", "HTML")
    threads = Runtime.getRuntime().availableProcessors()
    avoidCallsTo = setOf("kotlin.jvm.internal")
    timestampedReports = false
}
