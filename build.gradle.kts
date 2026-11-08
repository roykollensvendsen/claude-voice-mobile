plugins {
    id("claudevoice.layers")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// `check` here is the whole gate: the layer rules, the check's own tests, and every module's.
tasks.register("check") {
    group = "verification"
    description = "Runs the layer rules, their tests and every module's checks."
    dependsOn("checkLayers", gradle.includedBuild("build-logic").task(":test"))
    // :plugins only groups the plugins, and has no check of its own.
    dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName("check") } })
}
