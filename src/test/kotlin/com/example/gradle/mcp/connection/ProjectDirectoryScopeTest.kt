package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectDirectoryScopeTest {
    @TempDir
    lateinit var workspaceRoot: File

    @Test
    fun `allowedRoots includes connected and workspace directories`() {
        val connected = workspaceRoot.resolve("connected").also { it.mkdirs() }
        val workspace = workspaceRoot.resolve("workspace").also { it.mkdirs() }

        val scope = ProjectDirectoryScope(
            connectedProjectDirectory = { connected },
            workspaceProjectDirectory = { workspace },
        )

        scope.allowedRoots().shouldContainExactlyInAnyOrder(listOf(connected, workspace))
    }

    @Test
    fun `requireWithinBoundary accepts workspace root and subdirectories`() {
        val workspace = workspaceRoot.resolve("workspace").also { it.mkdirs() }
        val subProject = workspace.resolve("sub").also { it.mkdirs() }
        val scope = ProjectDirectoryScope(
            connectedProjectDirectory = { null },
            workspaceProjectDirectory = { workspace },
        )

        scope.requireWithinBoundary(workspace).shouldBe(workspace)
        scope.requireWithinBoundary(subProject).shouldBe(subProject)
    }

    @Test
    fun `requireWithinBoundary accepts sibling project under shared workspace root`() {
        val workspace = workspaceRoot
        val projectA = workspace.resolve("project-a").also { it.mkdirs() }
        val projectB = workspace.resolve("project-b").also { it.mkdirs() }
        val scope = ProjectDirectoryScope(
            connectedProjectDirectory = { projectB },
            workspaceProjectDirectory = { workspace },
        )

        scope.requireWithinBoundary(projectA).shouldBe(projectA)
    }

    @Test
    fun `requireWithinBoundary rejects paths outside allowed roots`() {
        val workspace = workspaceRoot.resolve("workspace").also { it.mkdirs() }
        val outside = workspaceRoot.resolve("outside").also { it.mkdirs() }
        val scope = ProjectDirectoryScope(
            connectedProjectDirectory = { workspace },
            workspaceProjectDirectory = { null },
        )

        val exception = shouldThrow<McpException> {
            scope.requireWithinBoundary(outside)
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        exception.message shouldBe "projectDirectory is outside the allowed workspace boundary: ${outside.path}"
    }

    @Test
    fun `requireWithinBoundary rejects explicit paths when no boundary is configured`() {
        val project = workspaceRoot.resolve("project").also { it.mkdirs() }
        val scope = ProjectDirectoryScope(
            connectedProjectDirectory = { null },
            workspaceProjectDirectory = { null },
        )

        val exception = shouldThrow<McpException> {
            scope.requireWithinBoundary(project)
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        exception.message shouldBe
            "projectDirectory requires GRADLE_PROJECT_DIR or an active Gradle connection to define the workspace boundary."
    }
}
