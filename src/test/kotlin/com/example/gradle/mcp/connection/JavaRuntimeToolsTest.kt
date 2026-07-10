package com.example.gradle.mcp.connection

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.support.BuildEnvironmentProxyOptions
import com.example.gradle.mcp.connection.support.buildEnvironmentProxy
import com.example.gradle.mcp.connection.support.projectConnectionProxy
import com.example.gradle.mcp.connection.support.recordingBuildLauncher
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class JavaRuntimeToolsTest {
    @Test
    fun `parse keeps JDK entries and prefers the full version from the title`() {
        val detected = JavaToolchainsParser.parse(
            """
             + Options
                 | Auto-detection:     Enabled
                 | Auto-download:      Enabled

             + Ubuntu JDK 17 (17.0.19+10-1-24.04.2-Ubuntu)
                 | Location:           /usr/lib/jvm/java-17-openjdk-amd64
                 | Language Version:   17
                 | Vendor:             Ubuntu
                 | Is JDK:             true

             + Foo JRE 11
                 | Location:           /usr/lib/jvm/java-11-openjdk-amd64
                 | Language Version:   11
                 | Is JDK:             false

             + Eclipse Temurin JDK 21
                 | Location:           /opt/jdks/temurin-21
                 | Language Version:   21
                 | Vendor:             Eclipse Temurin
                 | Is JDK:             true
            """.trimIndent(),
        )

        detected shouldContainExactly listOf(
            DetectedJdk(
                javaHome = "/usr/lib/jvm/java-17-openjdk-amd64",
                javaVersion = "17.0.19+10-1-24.04.2-Ubuntu",
            ),
            DetectedJdk(
                javaHome = "/opt/jdks/temurin-21",
                javaVersion = "21",
            ),
        )
    }

    @Test
    fun `collect reuses cached daemon environment and serializes token efficient response`() {
        val getModelCalls = AtomicInteger(0)
        val launcher = recordingBuildLauncher(
            stdoutText = """
                 + Ubuntu JDK 17 (17.0.19+10-1-24.04.2-Ubuntu)
                     | Location:           /usr/lib/jvm/java-17-openjdk-amd64
                     | Language Version:   17
                     | Is JDK:             true
            """.trimIndent(),
        )
        val connection = projectConnectionProxy(
            getModelCalls = getModelCalls,
            buildEnvironment = null,
            launcher = launcher.launcher,
        )

        val manager = GradleConnectionManager()
        manager.seedConnectionForTests(
            connection,
            projectDirectory = File("/workspace"),
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "9.6.0",
                gradleUserHome = "/gradle/home",
                javaHome = "/usr/lib/jvm/java-17-openjdk-amd64",
                javaVersion = "17.0.19",
                jvmArguments = emptyList(),
            ),
        )
        val snapshot = JavaRuntimesCollector.collect(
            projectDirectory = File("/workspace"),
            connection = connection,
            connectionManager = manager,
        )

        snapshot.toMap("/workspace") shouldBe linkedMapOf(
            "projectDirectory" to "/workspace",
            "detectionSource" to "javaToolchainsTask",
            "daemon" to mapOf(
                "javaHome" to "/usr/lib/jvm/java-17-openjdk-amd64",
                "javaVersion" to "17.0.19",
            ),
            "detectedJdks" to listOf(
                mapOf(
                    "javaHome" to "/usr/lib/jvm/java-17-openjdk-amd64",
                    "javaVersion" to "17.0.19+10-1-24.04.2-Ubuntu",
                ),
            ),
        )
        launcher.tasks shouldContainExactly listOf("javaToolchains")
        launcher.arguments shouldContainExactly listOf("-q")
        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `collect skips javaToolchains when includeToolchains is false`() {
        val launcher = recordingBuildLauncher(stdoutText = "should not be read")
        val connection = projectConnectionProxy(
            getModelCalls = AtomicInteger(0),
            buildEnvironment = null,
            launcher = launcher.launcher,
        )

        val manager = GradleConnectionManager()
        manager.seedConnectionForTests(
            connection,
            projectDirectory = File("/workspace"),
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "9.6.0",
                gradleUserHome = "/gradle/home",
                javaHome = "/usr/lib/jvm/java-17-openjdk-amd64",
                javaVersion = "17.0.19",
                jvmArguments = emptyList(),
            ),
        )
        val snapshot = JavaRuntimesCollector.collect(
            projectDirectory = File("/workspace"),
            connection = connection,
            connectionManager = manager,
            includeToolchains = false,
        )

        snapshot.detectionSource shouldBe "buildEnvironment"
        snapshot.detectedJdks shouldContainExactly emptyList()
        launcher.tasks shouldContainExactly emptyList()
    }

    @Test
    fun `collect loads BuildEnvironment when the cache is missing`(@TempDir javaHome: File) {
        File(javaHome, "release").writeText("JAVA_VERSION=\"21.0.10\"\n")
        val getModelCalls = AtomicInteger(0)
        val launcher = recordingBuildLauncher(
            stdoutText = """
                 + Ubuntu JDK 21 (21.0.10+7-Ubuntu-124.04)
                     | Location:           ${javaHome.path}
                     | Language Version:   21
                     | Is JDK:             true
            """.trimIndent(),
        )
        val connection = projectConnectionProxy(
            getModelCalls = getModelCalls,
            buildEnvironment = buildEnvironmentProxy(
                BuildEnvironmentProxyOptions(
                    javaHome = javaHome.path,
                    gradleVersion = "9.6.0",
                    jvmArguments = emptyList(),
                ),
            ),
            launcher = launcher.launcher,
        )

        val manager = GradleConnectionManager()
        manager.seedConnectionForTests(connection, projectDirectory = File("/workspace"))
        val snapshot = JavaRuntimesCollector.collect(
            projectDirectory = File("/workspace"),
            connection = connection,
            connectionManager = manager,
        )

        snapshot.daemon shouldBe DaemonJavaRuntime(
            javaHome = javaHome.path,
            javaVersion = "21.0.10",
        )
        manager.cachedEnvironment(File("/workspace"))?.javaVersion shouldBe "21.0.10"
        snapshot.detectedJdks shouldContainExactly listOf(
            DetectedJdk(
                javaHome = javaHome.path,
                javaVersion = "21.0.10+7-Ubuntu-124.04",
            ),
        )
        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `collect wraps javaToolchains failures as BUILD_FAILED`() {
        val connection = projectConnectionProxy(
            getModelCalls = AtomicInteger(0),
            buildEnvironment = null,
            launcher = recordingBuildLauncher(
                runException = RuntimeException("Task 'javaToolchains' not found in root project."),
            ).launcher,
        )

        val manager = GradleConnectionManager()
        manager.seedConnectionForTests(
            connection,
            projectDirectory = File("/workspace"),
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "9.6.0",
                gradleUserHome = null,
                javaHome = "/usr/lib/jvm/java-17-openjdk-amd64",
                javaVersion = "17.0.19",
                jvmArguments = emptyList(),
            ),
        )
        val error = shouldThrow<McpException> {
            JavaRuntimesCollector.collect(
                projectDirectory = File("/workspace"),
                connection = connection,
                connectionManager = manager,
            )
        }

        error.code shouldBe McpErrorCode.BUILD_FAILED
        error.message shouldContain "javaToolchains -q"
        error.message shouldContain "Task 'javaToolchains' not found"
    }

    @Test
    fun `requireNoActiveBuildForToolchainDetection allows daemon-only query while build is running`() {
        val manager = BuildExecutionManager(GradleConnectionManager())
        seedRunningBuild(manager)

        requireNoActiveBuildForToolchainDetection(
            includeToolchains = false,
            projectDirectory = testProjectDirectory,
            buildExecutionManager = manager,
        )
    }

    @Test
    fun `requireNoActiveBuildForToolchainDetection rejects toolchain listing while build is running`() {
        val manager = BuildExecutionManager(GradleConnectionManager())
        seedRunningBuild(manager)

        val error = shouldThrow<McpException> {
            requireNoActiveBuildForToolchainDetection(
                includeToolchains = true,
                projectDirectory = testProjectDirectory,
                buildExecutionManager = manager,
            )
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message shouldContain "includeToolchains=false"
    }
}

private fun seedRunningBuild(manager: BuildExecutionManager) {
    manager.seedRunningBuildForTests(
        testBuildRecord(
            id = "running-build",
            tracker = runningTracker(),
            projectDirectory = testProjectDirectory.absolutePath,
        ),
    )
}
