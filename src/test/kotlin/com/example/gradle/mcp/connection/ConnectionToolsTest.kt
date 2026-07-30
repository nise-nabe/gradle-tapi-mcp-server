package com.example.gradle.mcp.connection

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.support.defaultProxyReturn
import com.example.gradle.mcp.support.noopProjectConnection
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

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
