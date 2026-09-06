package com.example.gradle.mcp.dependency.mcp

import org.gradle.tooling.ProjectConnection
import java.io.File

/**
 * Bridge from the MCP server runtime into Gradle connection/lifecycle APIs
 * without creating a Gradle-module cycle back to the root project.
 */
interface DependencySourcesGradleAccess {
    fun resolveProjectDirectory(args: Map<String, Any>): File

    /**
     * Gradle user home for a connected project, or null when the project is not connected.
     * Used so `artifacts[]` keep-set lookup can consult the connection's cache, not only
     * process `GRADLE_USER_HOME` / `~/.gradle`.
     */
    fun gradleUserHome(projectDirectory: File): File? = null

    fun <T> withConnection(projectDirectory: File, block: (ProjectConnection) -> T): T

    fun <T> withNoActiveBuild(projectDirectory: File, block: () -> T): T
}