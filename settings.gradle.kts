plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lua.kt"

include("runtime")
include("repl")
include("benchmarks")
