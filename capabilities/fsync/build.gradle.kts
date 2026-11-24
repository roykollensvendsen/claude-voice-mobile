// Making a save last through a power cut (ADR-CVM-007): an fsync on a folder, so a file just moved
// into it is still there afterwards. A capability that reaches the phone's file system, used by
// the Android half of each plugin that keeps files, never by a plugin's rules. It is an Android
// library so the layer check counts it as reaching the phone, not as pure logic. It declares no
// permission and depends on no module of ours.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.claudevoice.mobile.capabilities.fsync"
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
