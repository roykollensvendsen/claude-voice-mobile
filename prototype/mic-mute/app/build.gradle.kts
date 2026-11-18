plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.claudevoice.prototype.micmute"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.claudevoice.prototype.micmute"
        minSdk = 31 // isClientSilenced and the owner's microphone switch both need Android 12
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototype"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
