package com.example.gradle.mcp.dependency

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.connection.SessionProjectContext
import com.example.gradle.mcp.dependency.mcp.DependencySourceToolCatalog
import com.example.gradle.mcp.dependency.mcp.DependencySourcesGradleAccess
import com.example.gradle.mcp.dependency.mcp.DependencySourcesIndexingConflictException
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.rejectUnsupportedProjectPath
import com.example.gradle.mcp.protocol.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CoroutineScope
import org.gradle.tooling.ProjectConnection
import java.io.File

context(runtime: GradleMcpRuntime)
fun Server.registerDependencySourceTools(scope: CoroutineScope, session: SessionProjectContext? = null) {
    val access = RuntimeDependencySourcesAccess(runtime, session)

    registerTool(
        scope,
        name = DependencySourceToolCatalog.INDEX_TOOL,
        description = DependencySourceToolCatalog.INDEX_DESCRIPTION,
        schema = DependencySourceToolCatalog.indexSchema(),
    ) { args ->
        dependencySourcesResult { runtime.dependencySourcesFacade.index(args, access) }
    }

    registerTool(
        scope,
        name = DependencySourceToolCatalog.INDEX_STATUS_TOOL,
        description = DependencySourceToolCatalog.INDEX_STATUS_DESCRIPTION,
        schema = DependencySourceToolCatalog.indexStatusSchema(),
    ) { args ->
        rejectUnsupportedProjectPath(args, DependencySourceToolCatalog.INDEX_STATUS_TOOL)
        dependencySourcesResult { runtime.dependencySourcesFacade.indexStatus(args) }
    }

    registerTool(
        scope,
        name = DependencySourceToolCatalog.SEARCH_TOOL,
        description = DependencySourceToolCatalog.SEARCH_DESCRIPTION,
        schema = DependencySourceToolCatalog.searchSchema(),
    ) { args ->
        rejectUnsupportedProjectPath(args, DependencySourceToolCatalog.SEARCH_TOOL)
        dependencySourcesResult { runtime.dependencySourcesFacade.search(args, access) }
    }

    registerTool(
        scope,
        name = DependencySourceToolCatalog.SEARCH_MULTI_TOOL,
        description = DependencySourceToolCatalog.SEARCH_MULTI_DESCRIPTION,
        schema = DependencySourceToolCatalog.searchMultiSchema(),
    ) { args ->
        rejectUnsupportedProjectPath(args, DependencySourceToolCatalog.SEARCH_MULTI_TOOL)
        dependencySourcesResult { runtime.dependencySourcesFacade.searchMulti(args, access) }
    }

    registerTool(
        scope,
        name = DependencySourceToolCatalog.READ_TOOL,
        description = DependencySourceToolCatalog.READ_DESCRIPTION,
        schema = DependencySourceToolCatalog.readSchema(),
    ) { args ->
        rejectUnsupportedProjectPath(args, DependencySourceToolCatalog.READ_TOOL)
        dependencySourcesResult { runtime.dependencySourcesFacade.read(args, access) }
    }
}

private inline fun dependencySourcesResult(block: () -> Map<String, Any?>): CallToolResult =
    jsonResult(runCatching(block).getOrElse { throw mapDependencySourcesError(it) })

private class RuntimeDependencySourcesAccess(
    private val runtime: GradleMcpRuntime,
    private val session: SessionProjectContext?,
) : DependencySourcesGradleAccess {
    override fun resolveProjectDirectory(args: Map<String, Any>): File =
        ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager, session)

    override fun gradleUserHome(projectDirectory: File): File? =
        runtime.connectionManager.gradleUserHome(projectDirectory)

    override fun <T> withConnection(projectDirectory: File, block: (ProjectConnection) -> T): T =
        runtime.connectionManager.withConnectionResult(projectDirectory, block)

    override fun <T> withNoActiveBuild(projectDirectory: File, block: () -> T): T =
        ProjectLifecycleGuard.withNoActiveBuild(
            projectDirectory = projectDirectory,
            buildExecutionManager = runtime.buildExecutionManager,
            message = { directory ->
                "Cannot index dependency sources while a Gradle build is active for ${directory.path}."
            },
            block = block,
        )
}

private fun mapDependencySourcesError(error: Throwable): Throwable =
    when (error) {
        is McpException -> error
        is IllegalArgumentException ->
            McpException(McpErrorCode.INVALID_ARGUMENT, error.message ?: "Invalid argument", error)
        is DependencySourcesIndexingConflictException ->
            McpException(McpErrorCode.BUILD_ALREADY_RUNNING, error.message ?: "Indexing already running", error)
        is IllegalStateException ->
            McpException(McpErrorCode.INTERNAL_ERROR, error.message ?: "Internal error", error)
        else -> error
    }