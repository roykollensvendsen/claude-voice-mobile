// Reading the owner's text messages (ADR-CVM-010). A plugin depends on :plugin-api, on
// capabilities and on its own rules, and nothing else.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.claudevoice.mobile.plugins.sms"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugins:sms:rules"))
}
