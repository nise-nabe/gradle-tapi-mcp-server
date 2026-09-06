plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.example"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    api(project(":dependency-sources-core"))
    implementation(libs.mcp.kotlin.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gradle.tooling.api)
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