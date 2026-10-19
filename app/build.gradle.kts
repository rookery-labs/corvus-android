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

kotlin {
    sourceSets["main"].kotlin.srcDirs(
        "src/main/kotlin"
    )
}

tasks.test {
    useJUnitPlatform()
    val rustTargetDir = file("../../laya-onnx-android/target/debug").absolutePath
    val sharedReleaseDir = "/home/runner/target/tomb_of_heroes/release"
    val sharedDebugDepsDir = "/home/runner/target/tomb_of_heroes/debug/deps"
    systemProperty(
        "java.library.path",
        "$sharedReleaseDir:$sharedDebugDepsDir:$rustTargetDir:${System.getProperty("java.library.path", "")}"
    )
    testLogging {
        events("passed", "skipped", "failed")
    }
}

