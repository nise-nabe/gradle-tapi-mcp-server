plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "com.example"
version = "0.1.0-spike"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass = "com.example.gradle.mcp.spike.GradleConnectionStatusSpikeKt"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.example.gradle.mcp.spike.GradleConnectionStatusSpikeKt")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
