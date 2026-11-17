// The screens, the settings and the service. The one module that may see every other.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.claudevoice.mobile.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.claudevoice.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// NoBackupTest reads the manifest the build merged for the variant under test, and the rules
// file it names, so what it checks is what ships.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifest = layout.buildDirectory
            .file("intermediates/merged_manifest/${variant.name}/process${name}MainManifest/AndroidManifest.xml")
        tasks.matching { it.name == "test${name}UnitTest" }.configureEach {
            dependsOn("process${name}MainManifest")
            // Declared as inputs, so a change to either runs the test again instead of reusing it.
            inputs.file(manifest)
            inputs.dir("src/main/res/xml")
            (this as Test).systemProperty("mergedManifest", manifest.get().asFile.path)
            systemProperty("resources", file("src/main/res").path)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugins:status"))
    testImplementation(libs.junit4)
}
