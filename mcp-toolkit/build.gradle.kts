plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.example"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
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
