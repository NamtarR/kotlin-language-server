import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
}

val javaVersion = property("javaVersion") as String

kotlin {
    jvmToolchain(javaVersion.toInt())
    compilerOptions {
        // Preserve source semantics and metadata until the embedded compiler is upgraded.
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

tasks.withType<Test>().configureEach {
    // Fixture discovery must not load the build's newer Kotlin tooling from its cache.
    environment("GRADLE_USER_HOME", layout.buildDirectory.dir("test-gradle-user-home").get().asFile.absolutePath)
}
