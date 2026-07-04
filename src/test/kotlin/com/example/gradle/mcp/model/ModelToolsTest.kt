package com.example.gradle.mcp.model

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ModelToolsTest {
    @Test
    fun `model query schemas expose prepareTasks property`() {
        listOf(
            projectTreeSchema(),
            modelQuerySchema(),
            buildInvocationsQuerySchema(),
            publicationsSchema(),
            helpSchema(),
        ).forEach { schema ->
            val prepareTasks = prepareTasksProperty(schema)

            prepareTasks["type"] shouldBe "array"
            (prepareTasks["items"] as Map<*, *>)["type"] shouldBe "string"
            (prepareTasks["description"] as String) shouldContain ":app:compileJava"
        }
    }

    @Test
    fun `requireNoActiveBuildForPrepareTasks allows empty prepareTasks while build is running`() {
        val manager = BuildExecutionManager(GradleConnectionManager())
        manager.seedRunningBuildForTests(runningBuildRecord())

        requireNoActiveBuildForPrepareTasks(
            prepareTasks = emptyList(),
            projectDirectory = testProjectDirectory,
            buildExecutionManager = manager,
        )
    }

    @Test
    fun `requireNoActiveBuildForPrepareTasks rejects prepareTasks while build is running`() {
        val manager = BuildExecutionManager(GradleConnectionManager())
        manager.seedRunningBuildForTests(runningBuildRecord())

        val error = shouldThrow<McpException> {
            requireNoActiveBuildForPrepareTasks(
                prepareTasks = listOf(":app:compileJava"),
                projectDirectory = testProjectDirectory,
                buildExecutionManager = manager,
            )
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message shouldContain "prepareTasks"
    }
}

private fun runningBuildRecord() =
    testBuildRecord(
        id = "running-build",
        tracker = runningTracker(),
        projectDirectory = testProjectDirectory.absolutePath,
    )

@Suppress("UNCHECKED_CAST")
private fun prepareTasksProperty(schema: Map<String, Any>): Map<String, Any?> =
    (schema["properties"] as Map<String, Any>)["prepareTasks"] as Map<String, Any?>
