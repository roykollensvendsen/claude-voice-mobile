// Reading the owner's contacts, for one question: which numbers match a spoken name
// (ADR-CVM-010). A capability (ADR-CVM-007): it knows nothing of the hub and depends on no module
// of ours. It is the only module that declares READ_CONTACTS.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.claudevoice.mobile.capabilities.contacts"
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
