package com.example.gradle.mcp.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildEnvironmentSnapshotTest {
    @Test
    fun `toMap matches gradle_get_build_environment shape`() {
        val snapshot = BuildEnvironmentSnapshot(
            gradleVersion = "8.14",
            gradleUserHome = "/gradle/home",
            javaHome = "/jdk/home",
            javaVersion = "21.0.2",
            jvmArguments = listOf("-Xmx2g"),
        )

        assertEquals(
            mapOf(
                "gradle" to mapOf(
                    "gradleVersion" to "8.14",
                    "gradleUserHome" to "/gradle/home",
                ),
                "java" to mapOf(
                    "javaHome" to "/jdk/home",
                    "javaVersion" to "21.0.2",
                    "jvmArguments" to listOf("-Xmx2g"),
                ),
            ),
            snapshot.toMap(),
        )
    }
}
