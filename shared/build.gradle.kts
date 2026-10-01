plugins {
    id("maven-publish")
    id("kotlin-language-server.kotlin-conventions")
    id("kotlin-language-server.publishing-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    // dependencies are constrained to versions defined
    // in /platform/build.gradle.kts
    implementation(platform(project(":platform")))

    implementation(libs.org.jetbrains.kotlin.stdlib)
    implementation(libs.org.jetbrains.exposed.core)
    implementation(libs.org.jetbrains.exposed.dao)
    testImplementation(libs.hamcrest.all)
    testImplementation(libs.junit.junit)
}
