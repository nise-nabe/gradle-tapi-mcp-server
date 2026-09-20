package com.example.gradle.mcp.connection

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.support.buildEnvironmentProxy
import com.example.gradle.mcp.connection.support.projectConnectionProxy
import com.example.gradle.mcp.connection.support.recordingBuildLauncher
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.defaultProxyReturn
import com.example.gradle.mcp.support.noopProjectConnection
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testExecutor
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConnectionToolsTest {
    @Test
    fun `disconnect includes warning when build was active`() {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        connectionManager.seedConnectionForTests(noopProjectConnection(), testProjectDirectory)
        buildExecutionManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "active-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

        val payload = disconnectProjects(runtime, testProjectDirectory.absolutePath)

        payload["warning"].shouldNotBeNull().toString() shouldContain "cancelled"
        payload["state"] shouldBe "disconnected"
        buildExecutionManager.hasActiveBuild().shouldBe(false)
    }

    @Test
    fun `disconnect cancels builds before closing connection`() {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        val connectionClosed = AtomicBoolean(false)
        val tokenSource = GradleConnector.newCancellationTokenSource()
        val connection = closeTrackingConnection(tokenSource, connectionClosed)
        connectionManager.seedConnectionForTests(connection, testProjectDirectory)
        buildExecutionManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "active-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
                cancellationTokenSource = tokenSource,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

        disconnectProjects(runtime, testProjectDirectory.absolutePath)

        tokenSource.token().isCancellationRequested.shouldBeTrue()
        connectionClosed.get().shouldBeTrue()
    }

    @Test
    fun `disconnect without active build omits warning`() {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        connectionManager.seedConnectionForTests(noopProjectConnection(), testProjectDirectory)
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

        val payload = disconnectProjects(runtime, testProjectDirectory.absolutePath)

        payload.containsKey("warning") shouldBe false
    }

    @Test
    fun `disconnect unknown project returns not_connected`() {
        val runtime = DefaultGradleMcpRuntime(GradleConnectionManager(), BuildExecutionManager(GradleConnectionManager()))

        val payload = disconnectProjects(runtime, testProjectDirectory.absolutePath)

        payload["state"] shouldBe "not_connected"
    }

    @Test
    fun `disconnect replaces build executor for last connected project`(@TempDir project: File) {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        connectionManager.seedConnectionForTests(noopProjectConnection(), project)
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)
        val executorBefore = buildExecutionManager.testExecutor()

        disconnectProjects(runtime, project.absolutePath)

        buildExecutionManager.testExecutor() shouldNotBe executorBefore
    }

    @Test
    fun `disconnect keeps build executor while another project stays connected`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        connectionManager.seedConnectionForTests(noopProjectConnection(), projectA)
        connectionManager.seedConnectionForTests(noopProjectConnection(), projectB)
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)
        val executorBefore = buildExecutionManager.testExecutor()

        disconnectProjects(runtime, projectA.absolutePath)

        buildExecutionManager.testExecutor() shouldBe executorBefore
    }

    @Test
    fun `connection status refresh skips getModel while a build is active`() {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        val getModelCalls = AtomicInteger(0)
        connectionManager.seedConnectionForTests(
            projectConnectionProxy(
                getModelCalls = getModelCalls,
                buildEnvironment = buildEnvironmentProxy(),
                launcher = recordingBuildLauncher().launcher,
            ),
            testProjectDirectory,
        )
        buildExecutionManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "active-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

        val payload = connectionStatusPayload(
            runtime,
            mapOf(
                "projectDirectory" to testProjectDirectory.absolutePath,
                "refresh" to true,
            ),
        )

        getModelCalls.get() shouldBe 0
        payload["runtimeStackAvailable"] shouldBe false
    }

    @Test
    fun `connection status refresh fetches environment when no build is active`() {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        val getModelCalls = AtomicInteger(0)
        connectionManager.seedConnectionForTests(
            projectConnectionProxy(
                getModelCalls = getModelCalls,
                buildEnvironment = buildEnvironmentProxy(),
                launcher = recordingBuildLauncher().launcher,
            ),
            testProjectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

        val payload = connectionStatusPayload(
            runtime,
            mapOf(
                "projectDirectory" to testProjectDirectory.absolutePath,
                "refresh" to true,
            ),
        )

        getModelCalls.get() shouldBe 1
        payload["runtimeStackAvailable"] shouldBe true
    }

    @Test
    fun `build environment returns cached snapshot while a build is active`() {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        val getModelCalls = AtomicInteger(0)
        connectionManager.seedConnectionForTests(
            connection = projectConnectionProxy(
                getModelCalls = getModelCalls,
                buildEnvironment = buildEnvironmentProxy(),
                launcher = recordingBuildLauncher().launcher,
            ),
            projectDirectory = testProjectDirectory,
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "9.6",
                gradleUserHome = "/gradle/home",
                javaHome = "/jdk/home",
                javaVersion = "21.0.2",
                jvmArguments = emptyList(),
            ),
        )
        buildExecutionManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "active-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

        val payload = buildEnvironmentPayload(
            runtime,
            mapOf("projectDirectory" to testProjectDirectory.absolutePath),
        )

        getModelCalls.get() shouldBe 0
        @Suppress("UNCHECKED_CAST")
        (payload["gradle"] as Map<String, Any?>)["gradleVersion"] shouldBe "9.6"
    }

    @Test
    fun `build environment throws BUILD_ALREADY_RUNNING while a build is active without cache`() {
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        val getModelCalls = AtomicInteger(0)
        connectionManager.seedConnectionForTests(
            projectConnectionProxy(
                getModelCalls = getModelCalls,
                buildEnvironment = buildEnvironmentProxy(),
                launcher = recordingBuildLauncher().launcher,
            ),
            testProjectDirectory,
        )
        buildExecutionManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "active-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

        val error = shouldThrow<McpException> {
            buildEnvironmentPayload(
                runtime,
                mapOf("projectDirectory" to testProjectDirectory.absolutePath),
            )
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        getModelCalls.get() shouldBe 0
    }

    private fun closeTrackingConnection(
        tokenSource: org.gradle.tooling.CancellationTokenSource,
        connectionClosed: AtomicBoolean,
    ): ProjectConnection =
        Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "close" -> {
                        tokenSource.token().isCancellationRequested.shouldBeTrue()
                        connectionClosed.set(true)
                        null
                    }
                    else -> defaultProxyReturn(method)
                }
            },
        ) as ProjectConnection
}
