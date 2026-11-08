// The one interface a plugin implements. Plain Kotlin: it reaches no Android API and no module.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.serialization.json)
}
