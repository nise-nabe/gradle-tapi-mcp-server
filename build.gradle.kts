plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.example"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(platform(libs.mcp.bom))
    implementation(libs.mcp)
    implementation(libs.gradle.tooling.api)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "com.example.mcp.GradleTapiMcpServerKt"
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit.get())
        }
    }
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.example.mcp.GradleTapiMcpServerKt")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
