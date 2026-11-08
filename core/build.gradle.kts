// Pairing, the protocol version, approvals and the log of every call, in plain Kotlin.
// It holds no phone function: those are plugins, and the core may not depend on one.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":plugin-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
