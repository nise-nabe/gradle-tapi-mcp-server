package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.optionalString
import java.io.File

object ProjectDirectoryResolver {
    internal var workspaceDirectoryOverride: File? = null

    fun workspaceFromEnvironment(): File? =
        workspaceDirectory(workspaceDirectoryOverride)
            ?: System.getenv("GRADLE_PROJECT_DIR")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.let(::workspaceDirectory)

    fun canonicalDirectory(path: String): File {
        val directory = File(path).absoluteFile
        if (!directory.isDirectory) {
            throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "projectDirectory is not a directory: $path",
            )
        }
        return directory.canonicalFile
    }

    fun bestEffortDirectory(path: String): File =
        bestEffortCanonical(File(path).absoluteFile)

    fun canonicalKey(directory: File): String =
        bestEffortCanonical(directory).absolutePath

    fun sameProject(storedPath: String?, directory: File): Boolean =
        storedPath?.let { canonicalKey(File(it)) == canonicalKey(directory) } == true

    private fun workspaceDirectory(directory: File?): File? =
        directory?.takeIf { it.isDirectory }?.let(::bestEffortCanonical)

    private fun bestEffortCanonical(directory: File): File =
        runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }

    fun resolveOptional(args: Map<String, Any>): File? =
        args.optionalString("projectDirectory")?.let(::canonicalDirectory)
            ?: workspaceFromEnvironment()

    fun resolveRequired(
        args: Map<String, Any>,
        connectionManager: GradleConnectionManager,
    ): File {
        val resolved = resolveConnectedTarget(args, connectionManager)
        if (!connectionManager.isConnected(resolved)) {
            throw McpException(
                McpErrorCode.NOT_CONNECTED,
                "Not connected to Gradle project: ${resolved.path}. Call gradle_connect first.",
            )
        }
        return resolved
    }

    fun resolveOptionalHint(
        args: Map<String, Any>,
        boundary: ((File) -> File)? = null,
    ): File? =
        args.optionalString("projectDirectory")?.let { path ->
            val directory = bestEffortDirectory(path)
            boundary?.invoke(directory) ?: directory
        }

    fun resolveWithBoundary(
        args: Map<String, Any>,
        connectionManager: GradleConnectionManager,
        boundary: (File) -> File,
    ): File {
        args.optionalString("projectDirectory")?.let { return boundary(canonicalDirectory(it)) }

        connectionManager.defaultProjectDirectory()?.let { return it }

        requireExplicitProjectWhenAmbiguous(connectionManager)

        soleConnectedWhenWorkspaceUnset(connectionManager)?.let { return it }

        workspaceFromEnvironment()?.let { return boundary(it) }

        throw McpException(
            McpErrorCode.NOT_CONNECTED,
            "No projectDirectory specified and GRADLE_PROJECT_DIR is not set. " +
                "Call gradle_connect or set GRADLE_PROJECT_DIR.",
        )
    }

    private fun resolveConnectedTarget(
        args: Map<String, Any>,
        connectionManager: GradleConnectionManager,
    ): File {
        args.optionalString("projectDirectory")?.let { return canonicalDirectory(it) }

        connectionManager.defaultProjectDirectory()?.let { return it }

        requireExplicitProjectWhenAmbiguous(connectionManager)

        soleConnectedWhenWorkspaceUnset(connectionManager)?.let { return it }

        workspaceFromEnvironment()?.let { return it }

        throw McpException(
            McpErrorCode.NOT_CONNECTED,
            "No projectDirectory specified and GRADLE_PROJECT_DIR is not set. " +
                "Call gradle_connect or set GRADLE_PROJECT_DIR.",
        )
    }

    private fun requireExplicitProjectWhenAmbiguous(connectionManager: GradleConnectionManager) {
        if (connectionManager.connectedProjectDirectories().size > 1) {
            val workspace = workspaceFromEnvironment()
            if (workspace == null || !connectionManager.isConnected(workspace)) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "projectDirectory is required when multiple Gradle projects are connected " +
                        "and GRADLE_PROJECT_DIR is not connected.",
                )
            }
        }
    }

    private fun soleConnectedWhenWorkspaceUnset(connectionManager: GradleConnectionManager): File? {
        if (connectionManager.connectedProjectDirectories().size != 1) {
            return null
        }
        val workspace = workspaceFromEnvironment()
        if (workspace != null && !connectionManager.isConnected(workspace)) {
            return connectionManager.connectedProjectDirectories().single()
        }
        return null
    }
}
