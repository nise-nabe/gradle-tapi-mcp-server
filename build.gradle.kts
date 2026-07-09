plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.example"
version = "0.4.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(libs.mcp.kotlin.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gradle.tooling.api)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "com.example.gradle.mcp.GradleTapiMcpServerLauncher"
}

testing {
    suites {
        @Suppress("UnstableApiUsage")
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(platform(libs.kotest.bom))
                implementation(libs.kotest.assertions.core)
            }
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.example.gradle.mcp.GradleTapiMcpServerLauncher",
            "Implementation-Version" to project.version,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.named<Test>("test") {
    dependsOn(tasks.jar)
}
