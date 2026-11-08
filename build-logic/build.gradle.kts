plugins {
    `kotlin-dsl`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("layers") {
            id = "claudevoice.layers"
            implementationClass = "LayersPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
