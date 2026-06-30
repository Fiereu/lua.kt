plugins {
    id("kotlin-conventions")
    id("test-conventions")
    id("spotless-conventions")
    id("sonarlint-conventions")
    application
}

group = "de.fiereu.lua"
version = "0.1.0"

dependencies {
    implementation(project(":runtime"))
}

application {
    mainClass = "de.fiereu.lua.repl.MainKt"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<JavaExec>("conformance") {
    group = "verification"
    description = "Run the official Lua testes through the runtime and report coverage."
    mainClass = "de.fiereu.lua.repl.ConformanceMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    val testesPath = project.findProperty("testes") as String?
    if (testesPath != null) {
        args(testesPath)
        workingDir(testesPath)
    }
}
