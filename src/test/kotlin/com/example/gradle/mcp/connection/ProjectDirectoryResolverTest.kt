package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.seedNoopConnections
import com.example.gradle.mcp.support.withWorkspaceDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectDirectoryResolverTest {
    @Test
    fun `bestEffortDirectory does not require directory to exist`(@TempDir dir: File) {
        val missing = File(dir, "removed-project").absoluteFile

        ProjectDirectoryResolver.bestEffortDirectory(missing.path) shouldBe missing.canonicalFile
    }

    @Test
    fun `canonicalDirectory rejects non-directory paths`(@TempDir dir: File) {
        val missing = File(dir, "missing").absolutePath

        val error = shouldThrow<McpException> {
            ProjectDirectoryResolver.canonicalDirectory(missing)
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe "projectDirectory is not a directory: $missing"
    }

    @Test
    fun `canonicalKey normalizes equivalent paths`(@TempDir dir: File) {
        val nested = dir.resolve("project").also { it.mkdirs() }
        ProjectDirectoryResolver.canonicalKey(nested) shouldBe
            ProjectDirectoryResolver.canonicalKey(File(nested.path))
    }

    @Test
    fun `canonicalKey falls back to absolute path for missing directories`(@TempDir dir: File) {
        val missing = File(dir, "removed-project").absoluteFile
        ProjectDirectoryResolver.canonicalKey(missing) shouldBe missing.canonicalFile.absolutePath
    }

    @Test
    fun `sameProject does not throw for stale stored paths`(@TempDir dir: File) {
        val active = dir.resolve("active").also { it.mkdirs() }
        val stale = File(dir, "removed-project").absolutePath

        ProjectDirectoryResolver.sameProject(stale, active).shouldBeFalse()
    }

    @Test
    fun `resolveRequired rejects ambiguous default when multiple projects are connected`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val manager = GradleConnectionManager().also { it.seedNoopConnections(projectA, projectB) }

        val error = shouldThrow<McpException> {
            ProjectDirectoryResolver.resolveRequired(emptyMap(), manager)
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe
            "projectDirectory is required when multiple Gradle projects are connected " +
            "and GRADLE_PROJECT_DIR is not connected."
    }

    @Test
    fun `resolveRequired uses sole connected project when no projectDirectory is specified`(@TempDir project: File) {
        val manager = GradleConnectionManager().also { it.seedNoopConnections(project) }

        ProjectDirectoryResolver.resolveRequired(emptyMap(), manager) shouldBe project.canonicalFile
    }

    @Test
    fun `resolveRequired uses sole connected project when workspace env is set but not connected`(
        @TempDir workspace: File,
        @TempDir connected: File,
    ) {
        val manager = GradleConnectionManager().also { it.seedNoopConnections(connected) }

        withWorkspaceDirectory(workspace) {
            ProjectDirectoryResolver.resolveRequired(emptyMap(), manager) shouldBe connected.canonicalFile
        }
    }

    @Test
    fun `resolveWithBoundary rejects when workspace env is set but not connected and multiple projects are connected`(
        @TempDir workspace: File,
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val manager = GradleConnectionManager().also { it.seedNoopConnections(projectA, projectB) }

        withWorkspaceDirectory(workspace) {
            val error = shouldThrow<McpException> {
                ProjectDirectoryResolver.resolveWithBoundary(
                    emptyMap(),
                    manager,
                    boundary = { it },
                )
            }
            error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `resolveWithBoundary rejects listing when multiple projects are connected`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val manager = GradleConnectionManager().also { it.seedNoopConnections(projectA, projectB) }
        val scope = ProjectDirectoryScope(manager)

        val error = shouldThrow<McpException> {
            ProjectDirectoryResolver.resolveWithBoundary(
                emptyMap(),
                manager,
                boundary = scope::requireWithinBoundary,
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `resolveRequired rejects unconnected workspace when no projects are connected`(@TempDir workspace: File) {
        val manager = GradleConnectionManager()

        withWorkspaceDirectory(workspace) {
            val error = shouldThrow<McpException> {
                ProjectDirectoryResolver.resolveRequired(emptyMap(), manager)
            }

            error.code shouldBe McpErrorCode.NOT_CONNECTED
            error.message shouldBe
                "Not connected to Gradle project: ${workspace.canonicalFile.path}. Call gradle_connect first."
        }
    }

    @Test
    fun `workspaceFromEnvironment ignores non-directory override`(@TempDir dir: File) {
        val file = File(dir, "not-a-directory.txt").also { it.writeText("x") }

        withWorkspaceDirectory(file) {
            ProjectDirectoryResolver.workspaceFromEnvironment().shouldBeNull()
        }
    }

    @Test
    fun `workspaceFromEnvironment canonicalizes directory override`(@TempDir dir: File) {
        val project = dir.resolve("project").also { it.mkdirs() }

        withWorkspaceDirectory(File(project.path)) {
            ProjectDirectoryResolver.workspaceFromEnvironment() shouldBe project.canonicalFile
        }
    }

    @Test
    fun `resolveOptionalHint returns null when projectDirectory is omitted`() {
        ProjectDirectoryResolver.resolveOptionalHint(emptyMap()).shouldBeNull()
    }

    @Test
    fun `resolveOptionalHint accepts missing directory paths`(@TempDir dir: File) {
        val missing = File(dir, "removed-project")
        ProjectDirectoryResolver.resolveOptionalHint(
            mapOf("projectDirectory" to missing.path),
        ) shouldBe missing.canonicalFile
    }

    @Test
    fun `resolveRequired prefers the session default over other pooled projects`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val manager = GradleConnectionManager().also { it.seedNoopConnections(projectA, projectB) }
        val session = SessionProjectContext(workspaceProject = null).also { it.onConnected(projectB) }

        ProjectDirectoryResolver.resolveRequired(emptyMap(), manager, session) shouldBe
            projectB.canonicalFile
    }

    @Test
    fun `resolveRequired rejects a project the session does not know`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val manager = GradleConnectionManager().also { it.seedNoopConnections(projectA, projectB) }
        val session = SessionProjectContext(workspaceProject = null).also { it.onConnected(projectA) }

        val error = shouldThrow<McpException> {
            ProjectDirectoryResolver.resolveRequired(
                mapOf("projectDirectory" to projectB.path),
                manager,
                session,
            )
        }

        error.code shouldBe McpErrorCode.NOT_CONNECTED
        error.message shouldBe
            "Not connected to Gradle project: ${projectB.canonicalFile.path}. Call gradle_connect first."
    }

    @Test
    fun `resolveRequired rejects ambiguous calls when the session knows multiple connected projects`(
        @TempDir projectA: File,
        @TempDir projectB: File,
        @TempDir projectC: File,
    ) {
        val manager =
            GradleConnectionManager().also { it.seedNoopConnections(projectA, projectB, projectC) }
        // Connect A, B, C then release C (the default): the session is left
        // knowing two connected projects with no default.
        val session = SessionProjectContext(workspaceProject = null)
        session.onConnected(projectA)
        session.onConnected(projectB)
        session.onConnected(projectC)
        session.onDisconnected(projectC)

        val error = shouldThrow<McpException> {
            ProjectDirectoryResolver.resolveRequired(emptyMap(), manager, session)
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `resolveRequired falls back to the session workspace even when it is not connected`(
        @TempDir workspace: File,
    ) {
        val manager = GradleConnectionManager()
        val session = SessionProjectContext(workspaceProject = workspace)

        val error = shouldThrow<McpException> {
            ProjectDirectoryResolver.resolveRequired(emptyMap(), manager, session)
        }

        error.code shouldBe McpErrorCode.NOT_CONNECTED
        error.message shouldBe
            "Not connected to Gradle project: ${workspace.canonicalFile.path}. Call gradle_connect first."
    }

    @Test
    fun `resolveRequired uses the sole session-known connected project`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        // Only A is pooled: the session knows B (unconnected) and A.
        val manager = GradleConnectionManager().also { it.seedNoopConnections(projectA) }
        val session = SessionProjectContext(
            workspaceProject = null,
            seedProjectDirectories = listOf(projectA, projectB),
        )
        session.onDisconnected(projectB)

        ProjectDirectoryResolver.resolveRequired(emptyMap(), manager, session) shouldBe
            projectA.canonicalFile
    }
}
