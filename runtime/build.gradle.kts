plugins {
    id("kotlin-conventions")
    id("test-conventions")
    id("spotless-conventions")
    id("sonarlint-conventions")
    `maven-publish`
}

group = "de.fiereu.lua"
version = "0.1.0"

kotlin {
    explicitApi()
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "lua"
        }
    }
}
