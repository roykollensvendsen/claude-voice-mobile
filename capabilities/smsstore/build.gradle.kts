// The phone's own message store (ADR-CVM-010). A capability (ADR-CVM-007): it knows nothing of the
// hub and depends on no module of ours. To sms_send it answers one question only, whether the
// owner has sent a message to a number, as a yes or no. It declares no permission: it answers only
// when the app already holds READ_SMS, which a build with the `sms` plugin and the owner's grant
// gives.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.claudevoice.mobile.capabilities.smsstore"
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
