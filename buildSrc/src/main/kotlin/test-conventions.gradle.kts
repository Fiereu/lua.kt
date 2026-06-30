import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("kotlin-conventions")
    // Re-declared here so the type-safe `testImplementation` accessor is generated.
    kotlin("jvm")
}

val libs = the<LibrariesForLibs>()

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}
