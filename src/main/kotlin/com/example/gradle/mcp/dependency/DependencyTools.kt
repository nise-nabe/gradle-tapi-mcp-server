package com.example.gradle.mcp.dependency

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.dependency.mcp.DependencySourceToolCatalog
import com.example.gradle.mcp.dependency.mcp.DependencySourcesFacade
import com.example.gradle.mcp.dependency.mcp.DependencySourcesGradleAccess
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.rejectUnsupportedProjectPath
import com.example.gradle.mcp.protocol.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import org.gradle.tooling.ProjectConnection
import java.io.File

private val dependencySourcesFacade = DependencySourcesFacade()

context(runtime: GradleMcpRuntime)
fun Server.registerDependencySourceTools(scope: CoroutineScope) {
    val access = RuntimeDependencySourcesAccess(runtime)

    registerTool(
        scope,
        name = DependencySourceToolCatalog.INDEX_TOOL,
        description = DependencySourceToolCatalog.INDEX_DESCRIPTION,
        schema = DependencySourceToolCatalog.indexSchema(),
    ) { args ->
        rejectUnsupportedProjectPath(args, DependencySourceToolCatalog.INDEX_TOOL)
        jsonResult(
            runCatching { dependencySourcesFacade.index(args, access) }
                .getOrElse { throw mapDependencySourcesError(it) },
        )
    }

    registerTool(
        scope,
        name = DependencySourceToolCatalog.SEARCH_TOOL,
        description = DependencySourceToolCatalog.SEARCH_DESCRIPTION,
        schema = DependencySourceToolCatalog.searchSchema(),
    ) { args ->
        rejectUnsupportedProjectPath(args, DependencySourceToolCatalog.SEARCH_TOOL)
        jsonResult(
            runCatching { dependencySourcesFacade.search(args, access) }
                .getOrElse { throw mapDependencySourcesError(it) },
        )
    }

    registerTool(
        scope,
        name = DependencySourceToolCatalog.SEARCH_MULTI_TOOL,
        description = DependencySourceToolCatalog.SEARCH_MULTI_DESCRIPTION,
        schema = DependencySourceToolCatalog.searchMultiSchema(),
    ) { args ->
        rejectUnsupportedProjectPath(args, DependencySourceToolCatalog.SEARCH_MULTI_TOOL)
        jsonResult(
            runCatching { dependencySourcesFacade.searchMulti(args, access) }
                .getOrElse { throw mapDependencySourcesError(it) },
        )
    }
}

private class RuntimeDependencySourcesAccess(
    private val runtime: GradleMcpRuntime,
) : DependencySourcesGradleAccess {
    override fun resolveProjectDirectory(args: Map<String, Any>): File =
        ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)

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
        is IllegalStateException ->
            McpException(McpErrorCode.INTERNAL_ERROR, error.message ?: "Internal error", error)
        else -> error
    }