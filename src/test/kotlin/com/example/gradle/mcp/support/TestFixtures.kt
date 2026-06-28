package com.example.gradle.mcp.support

import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import java.io.File

val testProjectDirectory: File = File(".").canonicalFile

internal fun <T> withWorkspaceDirectory(directory: File?, block: () -> T): T {
    val previous = ProjectDirectoryResolver.workspaceDirectoryOverride
    ProjectDirectoryResolver.workspaceDirectoryOverride = directory
    return try {
        block()
    } finally {
        ProjectDirectoryResolver.workspaceDirectoryOverride = previous
    }
}
