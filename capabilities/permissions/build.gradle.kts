// What Android allows the app now, and which permissions this install has asked for
// (ADR-CVM-011). A capability (ADR-CVM-007): it knows nothing of the hub and depends on no module
// of ours. It reads states only; it never asks for a permission and never opens a settings page.
// Every build holds it, since every agent_info answer reports access.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.claudevoice.mobile.capabilities.permissions"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit4)
}
