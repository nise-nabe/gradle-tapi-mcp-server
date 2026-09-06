package com.example.gradle.mcp.dependency.mcp

import org.gradle.tooling.ProjectConnection
import java.io.File

/**
 * Bridge from the MCP server runtime into Gradle connection/lifecycle APIs
 * without creating a Gradle-module cycle back to the root project.
 */
interface DependencySourcesGradleAccess {
    fun resolveProjectDirectory(args: Map<String, Any>): File

    fun <T> withConnection(projectDirectory: File, block: (ProjectConnection) -> T): T

    fun <T> withNoActiveBuild(projectDirectory: File, block: () -> T): T
}