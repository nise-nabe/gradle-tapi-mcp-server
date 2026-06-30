package com.example.gradle.mcp.connection

import com.example.gradle.mcp.connection.support.BuildEnvironmentProxyOptions
import com.example.gradle.mcp.connection.support.buildEnvironmentProxy
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

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

        snapshot.toMap() shouldBe mapOf(
            "gradle" to mapOf(
                "gradleVersion" to "8.14",
                "gradleUserHome" to "/gradle/home",
            ),
            "java" to mapOf(
                "javaHome" to "/jdk/home",
                "javaVersion" to "21.0.2",
                "jvmArguments" to listOf("-Xmx2g"),
            ),
        )
    }

    @Test
    fun `toMap includes versionInfo under gradle when present`() {
        val snapshot = BuildEnvironmentSnapshot(
            gradleVersion = "9.6",
            gradleUserHome = "/gradle/home",
            javaHome = "/jdk/home",
            javaVersion = "21.0.2",
            jvmArguments = emptyList(),
            versionInfo = "Gradle 9.6\nBuild time: 2026-01-01",
        )

        snapshot.toMap() shouldBe mapOf(
            "gradle" to mapOf(
                "gradleVersion" to "9.6",
                "gradleUserHome" to "/gradle/home",
                "versionInfo" to "Gradle 9.6\nBuild time: 2026-01-01",
            ),
            "java" to mapOf(
                "javaHome" to "/jdk/home",
                "javaVersion" to "21.0.2",
                "jvmArguments" to emptyList<String>(),
            ),
        )
    }

    @Test
    fun `buildEnvironmentSnapshotFrom maps versionInfo when supported`() {
        val environment = buildEnvironmentProxy(
            BuildEnvironmentProxyOptions(versionInfo = "Gradle 9.6"),
        )

        val snapshot = buildEnvironmentSnapshotFrom(environment)

        snapshot.versionInfo shouldBe "Gradle 9.6"
    }

    @Test
    fun `buildEnvironmentSnapshotFrom omits versionInfo when unsupported`() {
        val environment = buildEnvironmentProxy(
            BuildEnvironmentProxyOptions(versionInfoThrows = true),
        )

        val snapshot = buildEnvironmentSnapshotFrom(environment)

        snapshot.versionInfo.shouldBeNull()
    }
}
