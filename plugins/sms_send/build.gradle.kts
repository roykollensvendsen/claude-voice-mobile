// Sending a text message for the owner, only after their spoken yes and an undo delay
// (messages-v1 §8, choice B). A plugin depends on :plugin-api and on its own rules, and nothing else.
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
    testImplementation(libs.junit4)
}
