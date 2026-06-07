plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
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
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "com.example.gradle.mcp.GradleTapiMcpServerKt"
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
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
        attributes("Main-Class" to "com.example.gradle.mcp.GradleTapiMcpServerKt")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
