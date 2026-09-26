package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import java.io.File
import java.nio.file.Path

class ProjectDirectoryScope(
    private val connectedProjectDirectories: () -> List<File>,
    private val workspaceProjectDirectory: () -> File? = ProjectDirectoryResolver::workspaceFromEnvironment,
    private val sessionProjectDirectories: (() -> List<File>)? = null,
    private val preferredProjectDirectory: () -> File? = { null },
) {
    constructor(connectionManager: GradleConnectionManager) : this(
        connectedProjectDirectories = { connectionManager.connectedProjectDirectories() },
    )

    /**
     * Session-scoped roots: only projects the session knows (its workspace
     * plus projects it connected) define the boundary, so hints/status on a
     * shared HTTP server cannot reach another session's project.
     */
    constructor(
        connectionManager: GradleConnectionManager,
        session: SessionProjectContext?,
    ) : this(
        connectedProjectDirectories = { connectionManager.connectedProjectDirectories() },
        sessionProjectDirectories = session?.let { s -> { s.knownProjects() } },
        preferredProjectDirectory = { session?.defaultProject() },
    )

    /** Session default project, or null for pool-scoped (legacy) scopes. */
    fun preferredRoot(): File? = preferredProjectDirectory()

    fun allowedRoots(): List<File> {
        sessionProjectDirectories?.let { roots ->
            return roots().distinctBy { ProjectDirectoryResolver.canonicalKey(it) }
        }
        return buildList {
            connectedProjectDirectories().forEach { add(it) }
            workspaceProjectDirectory()?.let { add(it) }
        }.distinctBy { ProjectDirectoryResolver.canonicalKey(it) }
    }

    fun isWithinBoundary(directory: File): Boolean {
        val canonicalDirectory = directory.canonicalFile.toPath()
        return allowedRoots().any { root ->
            isSameOrContainedIn(canonicalDirectory, root.canonicalFile.toPath())
        }
    }

    fun requireWithinBoundary(directory: File): File {
        val allowedRoots = allowedRoots()
        if (allowedRoots.isEmpty()) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "projectDirectory requires GRADLE_PROJECT_DIR or an active Gradle connection to define the workspace boundary.",
            )
        }
        if (!isWithinBoundary(directory)) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "projectDirectory is outside the allowed workspace boundary: ${directory.path}",
            )
        }
        return directory
    }

    private fun isSameOrContainedIn(candidate: Path, root: Path): Boolean =
        candidate == root || candidate.startsWith(root)
}
