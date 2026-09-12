package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.CapturingStreams
import com.example.gradle.mcp.connection.BuildEnvironmentSnapshot
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.support.BuildEnvironmentProxyOptions
import com.example.gradle.mcp.connection.support.buildEnvironmentProxy
import com.example.gradle.mcp.connection.support.projectConnectionProxy
import com.example.gradle.mcp.connection.support.recordingBuildLauncher
import com.example.gradle.mcp.support.gradleProjectConnectionProxy
import com.example.gradle.mcp.support.gradleProjectProxy
import com.example.gradle.mcp.support.noopProjectConnection
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.withWorkspaceDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class GradleTapiResourceReadTest {
    @Test
    fun `connection status resource uses cache without refresh`(@TempDir project: File) {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            projectConnectionProxy(
                getModelCalls = getModelCalls,
                buildEnvironment = null,
                launcher = recordingBuildLauncher().launcher,
            ),
            projectDirectory = project,
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "8.14",
                gradleUserHome = "/gradle/home",
                javaHome = "/jdk/home",
                javaVersion = "17",
                jvmArguments = emptyList(),
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val uri = GradleTapiResourceUri(project, GradleTapiResourceKind.ConnectionStatus).toUri()

        val payload = readGradleTapiResource(runtime, uri)

        payload["connected"] shouldBe true
        payload["gradleVersion"] shouldBe "8.14"
        payload["runtimeStackAvailable"] shouldBe true
        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `connection status refresh=true fetches missing environment`(@TempDir project: File) {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        val environment = buildEnvironmentProxy(
            BuildEnvironmentProxyOptions(gradleVersion = "9.6", javaHome = File(project, "jdk").path),
        )
        File(project, "jdk").mkdirs()
        File(project, "jdk/release").writeText("JAVA_VERSION=\"21.0.2\"\n")
        connectionManager.seedConnectionForTests(
            projectConnectionProxy(
                getModelCalls = getModelCalls,
                buildEnvironment = environment,
                launcher = recordingBuildLauncher().launcher,
            ),
            projectDirectory = project,
            environment = null,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val uri = GradleTapiResourceUri(
            projectDirectory = project,
            kind = GradleTapiResourceKind.ConnectionStatus,
            query = mapOf("refresh" to "true"),
        ).toUri()

        val payload = readGradleTapiResource(runtime, uri)

        payload["connected"] shouldBe true
        payload["runtimeStackAvailable"] shouldBe true
        payload["gradleVersion"] shouldBe "9.6"
        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `environment resource returns the same shape as the tool`(@TempDir project: File) {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            projectConnectionProxy(
                getModelCalls = getModelCalls,
                buildEnvironment = buildEnvironmentProxy(
                    BuildEnvironmentProxyOptions(gradleVersion = "9.6", versionInfo = "Gradle 9.6"),
                ),
                launcher = recordingBuildLauncher().launcher,
            ),
            projectDirectory = project,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val uri = GradleTapiResourceUri(project, GradleTapiResourceKind.Environment).toUri()

        val payload = readGradleTapiResource(runtime, uri)

        val gradle = payload["gradle"] as Map<*, *>
        gradle["gradleVersion"] shouldBe "9.6"
        gradle["versionInfo"] shouldBe "Gradle 9.6"
        payload.containsKey("java") shouldBe true
        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `overview resource shares the model query guard`(@TempDir project: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            gradleProjectConnectionProxy(gradleProjectProxy(name = "app", directory = project)),
            projectDirectory = project,
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "8.14",
                gradleUserHome = "/gradle/home",
                javaHome = "/jdk",
                javaVersion = "17",
                jvmArguments = emptyList(),
            ),
        )
        val buildManager = BuildExecutionManager(connectionManager)
        buildManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = project.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildManager)
        val uri = GradleTapiResourceUri(project, GradleTapiResourceKind.Overview).toUri()

        val error = shouldThrow<McpException> {
            readGradleTapiResource(runtime, uri)
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.errorDetails["activeBuildId"] shouldBe "running-build"
    }

    @Test
    fun `overview resource returns project tree when idle`(@TempDir project: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            gradleProjectConnectionProxy(
                gradleProjectProxy(name = "demo", path = ":", directory = project),
            ),
            projectDirectory = project,
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "8.14",
                gradleUserHome = "/gradle/home",
                javaHome = "/jdk",
                javaVersion = "17",
                jvmArguments = emptyList(),
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val uri = GradleTapiResourceUri(project, GradleTapiResourceKind.Overview).toUri()

        val payload = readGradleTapiResource(runtime, uri)

        payload["name"] shouldBe "demo"
        payload["path"] shouldBe ":"
    }

    @Test
    fun `recent builds and status omit stdout by default`(@TempDir project: File) {
        withWorkspaceDirectory(project) {
            val connectionManager = GradleConnectionManager()
            connectionManager.seedConnectionForTests(
                noopProjectConnection(),
                projectDirectory = project,
            )
            val buildManager = BuildExecutionManager(connectionManager)
            buildManager.seedRunningBuildForTests(
                testBuildRecord(
                    id = "bg-1",
                    tracker = runningTracker(),
                    projectDirectory = project.absolutePath,
                    streams = CapturingStreams().also {
                        it.appendStdoutForTests("lots of log output\n")
                    },
                ),
            )
            val runtime = DefaultGradleMcpRuntime(connectionManager, buildManager)

            val recent = readGradleTapiResource(
                runtime,
                GradleTapiResourceUri(project, GradleTapiResourceKind.RecentBuilds).toUri(),
            )
            val builds = recent["builds"] as List<*>
            builds.shouldHaveSize(1)
            val first = builds.single() as Map<*, *>
            first["buildId"] shouldBe "bg-1"

            val status = readGradleTapiResource(
                runtime,
                GradleTapiResourceUri(
                    project,
                    GradleTapiResourceKind.BuildStatus("bg-1"),
                ).toUri(),
            )
            status["buildId"] shouldBe "bg-1"
            status["status"] shouldBe "running"
            status["stdout"].shouldBeNull()
            status.containsKey("progress") shouldBe false
        }
    }

    @Test
    fun `unknown resource path is invalid argument`(@TempDir project: File) {
        val runtime = DefaultGradleMcpRuntime(
            GradleConnectionManager(),
            BuildExecutionManager(GradleConnectionManager()),
        )
        val error = shouldThrow<McpException> {
            readGradleTapiResource(
                runtime,
                "gradle-tapi://${GradleTapiResourceUri.encodeSegment(project.path)}/not-a-resource",
            )
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }
}
