package com.example.gradle.mcp.connection

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import java.io.File

internal object ProjectLifecycleGuard {
    inline fun <T> withNoActiveBuild(
        projectDirectory: File,
        buildExecutionManager: BuildExecutionManager,
        message: (File) -> String,
        block: () -> T,
    ): T = synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
        val activeBuild = buildExecutionManager.activeBuildSnapshot(projectDirectory)
        if (activeBuild != null) {
            throw McpException(
                McpErrorCode.BUILD_ALREADY_RUNNING,
                message(projectDirectory),
                errorDetails = activeBuild.toErrorFields(),
            )
        }
        block()
    }
}
