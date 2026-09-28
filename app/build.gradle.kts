plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Corvus Browser Android Configuration (Fork Fenix)
val applicationId = "dev.corvus.browser"
val namespace = "org.mozilla.fenix"
val compileSdk = 34
val minSdk = 28
val targetSdk = 34

dependencies {
    // Kotlin Coroutines & JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // System 1 Runtime: ONNX Runtime Android
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // TDD Test Suite (JUnit 5 & Coroutines Test)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
