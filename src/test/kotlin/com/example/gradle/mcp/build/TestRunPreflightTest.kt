package com.example.gradle.mcp.build

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.gradleProjectConnectionProxy
import com.example.gradle.mcp.support.gradleProjectProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TestRunPreflightTest {
    private val projectDirectory = File("/workspace").absoluteFile

    @Test
    fun `preflightRunTests skips getModel when single-project is cached`() {
        val getModelCalls = AtomicInteger(0)
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = gradleProjectConnectionProxy(gradleProjectProxy(), getModelCalls),
            projectDirectory = projectDirectory,
            cachedHasSubprojects = false,
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))

        with(runtime) {
            preflightRunTests(
                projectDirectory,
                TestRunOptions(selection = TestRunSelection.Classes(listOf("com.example.FooTest"))),
            )
        }

        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `preflightRunTests caches single-project and skips getModel on subsequent calls`() {
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

        getModelCalls.get() shouldBe 1
        connectionManager.cachedHasSubprojects(projectDirectory) shouldBe false
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
}
