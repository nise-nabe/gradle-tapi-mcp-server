package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import java.io.File
import java.nio.file.Path

class ProjectDirectoryScope(
    private val connectedProjectDirectory: () -> File?,
    private val workspaceProjectDirectory: () -> File? = ::workspaceDirectoryFromEnvironment,
) {
    constructor(connectionManager: GradleConnectionManager) : this(
        connectedProjectDirectory = { connectionManager.connectedProjectDirectory() },
    )

    fun allowedRoots(): List<File> =
        buildList {
            connectedProjectDirectory()?.let { add(it) }
            workspaceProjectDirectory()?.let { add(it) }
        }

    fun requireWithinBoundary(directory: File): File {
        val allowedRoots = allowedRoots()
        if (allowedRoots.isEmpty()) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "projectDirectory requires GRADLE_PROJECT_DIR or an active Gradle connection to define the workspace boundary.",
            )
        }
        val canonicalDirectory = directory.canonicalFile.toPath()
        val withinBoundary = allowedRoots.any { root ->
            isSameOrContainedIn(canonicalDirectory, root.canonicalFile.toPath())
        }
        if (!withinBoundary) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "projectDirectory is outside the allowed workspace boundary: ${directory.path}",
            )
        }
        return directory
    }

    private fun isSameOrContainedIn(candidate: Path, root: Path): Boolean =
        candidate == root || candidate.startsWith(root)

    companion object {
        private fun workspaceDirectoryFromEnvironment(): File? =
            System.getenv("GRADLE_PROJECT_DIR")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.isDirectory }
    }
}
