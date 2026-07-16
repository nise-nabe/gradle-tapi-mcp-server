package com.example.gradle.mcp.build

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.gradleProjectConnectionProxy
import com.example.gradle.mcp.support.gradleProjectProxy
import com.example.gradle.mcp.support.testRunProjectConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TestRunPreflightTest {
    private val projectDirectory = File("/workspace").absoluteFile

    @Test
    fun `preflightRunTests refetches single-project on each call because false is not cached`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options)
            preflightRunTests(projectDirectory, options)
        }

        getModelCalls.get() shouldBe 2
        connectionManager.cachedHasSubprojects(projectDirectory).shouldBeNull()
    }

    @Test
    fun `preflightRunTests skips getModel when multi-project is cached`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
            cachedHasSubprojects = true,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        val error = shouldThrow<McpException> {
            with(runtime) {
                preflightRunTests(
                    projectDirectory,
                    TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
                )
            }
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `preflightRunTests rejects unscoped classes in multi-project builds`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                gradleProjectProxy(
                    children = listOf(gradleProjectProxy(name = "app", path = ":app")),
                ),
            ),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        val error = shouldThrow<McpException> {
            with(runtime) {
                preflightRunTests(
                    projectDirectory,
                    TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
                )
            }
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        connectionManager.cachedHasSubprojects(projectDirectory) shouldBe true
    }

    @Test
    fun `preflightRunTests skips active build guard when deferScopeModelCheck is true`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(gradleProjectProxy()),
            projectDirectory = projectDirectory,
        )
        val buildManager = BuildExecutionManager(connectionManager)
        buildManager.seedRunningBuildForTests(
            com.example.gradle.mcp.support.testBuildRecord(
                id = "running-build",
                tracker = com.example.gradle.mcp.support.runningTracker(),
                projectDirectory = projectDirectory.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildManager)
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = true)
        }
    }

    @Test
    fun `preflightRunTests detects newly added subprojects when false was never cached`() {
        val getModelCalls = AtomicInteger(0)
        val singleProject = gradleProjectProxy()
        val multiProject = gradleProjectProxy(
            children = listOf(gradleProjectProxy(name = "app", path = ":app")),
        )
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(
                project = singleProject,
                getModelCalls = getModelCalls,
                projectSequence = listOf(singleProject, multiProject),
            ),
            projectDirectory = projectDirectory,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options)
            connectionManager.cachedHasSubprojects(projectDirectory).shouldBeNull()

            val error = shouldThrow<McpException> {
                preflightRunTests(projectDirectory, options)
            }
            error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }

        getModelCalls.get() shouldBe 2
        connectionManager.cachedHasSubprojects(projectDirectory) shouldBe true
    }

    @Test
    fun `non-deferred unscoped test run queries GradleProject once across preflight and execution`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = testRunProjectConnection(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
        )
        val manager = BuildExecutionManager(connectionManager)
        val runtime = DefaultGradleMcpRuntime(connectionManager, manager)
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = false)
        }
        getModelCalls.get() shouldBe 1

        runBlocking {
            manager.runForeground(
                request = BuildRunRequest(
                    projectDirectory = projectDirectory,
                    kind = BuildKind.TESTS,
                    selection = options.selection,
                    testScopeValidatedAtPreflight = true,
                ),
                notifier = null,
            )
        }

        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `deferred unscoped test run queries GradleProject at execution time`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = testRunProjectConnection(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
        )
        val manager = BuildExecutionManager(connectionManager)
        val runtime = DefaultGradleMcpRuntime(connectionManager, manager)
        val options = TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest")))

        with(runtime) {
            preflightRunTests(projectDirectory, options, deferScopeModelCheck = true)
        }
        getModelCalls.get() shouldBe 0

        runBlocking {
            manager.runForeground(
                request = BuildRunRequest(
                    projectDirectory = projectDirectory,
                    kind = BuildKind.TESTS,
                    selection = options.selection,
                    testScopeValidatedAtPreflight = false,
                ),
                notifier = null,
            )
        }

        getModelCalls.get() shouldBe 1
    }
}
