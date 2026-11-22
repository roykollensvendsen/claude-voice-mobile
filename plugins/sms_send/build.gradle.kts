// Sending a text message for the owner, only after their spoken yes and an undo delay
// (messages-v1 §8, choice B), to a number or a contact found by name (ADR-CVM-010). A plugin
// depends on :plugin-api, on its own rules and on capabilities, and nothing else.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.claudevoice.mobile.plugins.sms_send"
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
    implementation(project(":plugins:sms_send:rules"))
    implementation(project(":capabilities:contacts"))
    implementation(project(":capabilities:smsstore"))
    testImplementation(libs.junit4)
}
