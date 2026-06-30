plugins {
    id("kotlin-conventions")
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
    mainClass = "de.fiereu.lua.benchmark.BenchKt"
}

tasks.register<JavaExec>("profile") {
    group = "verification"
    description = "Profile reference lua, lua.kt (fresh VM), and lua.kt (warmed VM) on the same workloads."
    mainClass = "de.fiereu.lua.benchmark.BenchKt"
    classpath = sourceSets["main"].runtimeClasspath
    // Point the reference leg at a specific lua binary with -Plua=/path/to/lua; otherwise PATH is searched.
    (project.findProperty("lua") as String?)?.let { systemProperty("lua.bin", it) }
}
