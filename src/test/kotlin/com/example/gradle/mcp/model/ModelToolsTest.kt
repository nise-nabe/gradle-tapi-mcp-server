package com.example.gradle.mcp.model

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.gradleProjectProxy
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

class ModelToolsTest {
    @Test
    fun `model query schemas expose prepareTasks property`() {
        listOf(
            projectTreeSchema(),
            scopedProjectTreeSchema(),
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
    fun `scoped project tree schema exposes projectPath`() {
        val schema = scopedProjectTreeSchema()
        val properties = schema["properties"] as Map<*, *>

        properties.containsKey("projectPath") shouldBe true
        (properties["projectPath"] as Map<*, *>)["type"] shouldBe "string"
    }

    @Test
    fun `requireNoActiveBuildForPrepareTasks rejects model queries while build is running`() {
        val manager = BuildExecutionManager(GradleConnectionManager())
        manager.seedRunningBuildForTests(runningBuildRecord())

        val error = shouldThrow<McpException> {
            requireNoActiveBuildForPrepareTasks(
                prepareTasks = emptyList(),
                projectDirectory = testProjectDirectory,
                buildExecutionManager = manager,
            )
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message shouldContain "Cannot query Gradle models"
        error.errorDetails["activeBuildId"] shouldBe "running-build"
        error.errorDetails["activeStatus"] shouldBe "running"
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
        error.errorDetails["activeBuildId"] shouldBe "running-build"
    }

    @Test
    fun `rejectUnsupportedProjectPath rejects projectPath on gradle_get_gradle_build`() {
        val error = shouldThrow<McpException> {
            rejectUnsupportedProjectPath(mapOf("projectPath" to ":plugin"), "gradle_get_gradle_build")
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "gradle_get_gradle_build"
        error.message shouldContain "gradle_get_project_overview"
    }

    @Test
    fun `rejectUnsupportedProjectPath allows blank projectPath`() {
        rejectUnsupportedProjectPath(mapOf("projectPath" to "   "), "gradle_get_gradle_build")
    }

    @Test
    fun `scopedGradleProject rejects unknown projectPath with invalid argument`() {
        val root = gradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            children = listOf(
                gradleProjectProxy(
                    name = "plugin",
                    path = ":plugin",
                    directory = File("/root/plugin"),
                ),
            ),
        )

        val error = shouldThrow<McpException> {
            scopedGradleProject(root, ProjectTreeOptions(projectPath = ":missing"))
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain ":missing"
    }

    @Test
    fun `scopedGradleProject resolves scoped subproject`() {
        val root = gradleProjectProxy(
            name = "root",
            path = ":",
            directory = File("/root"),
            children = listOf(
                gradleProjectProxy(
                    name = "plugin",
                    path = ":plugin",
                    directory = File("/root/plugin"),
                ),
            ),
        )

        scopedGradleProject(root, ProjectTreeOptions(projectPath = ":plugin")).path shouldBe ":plugin"
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
