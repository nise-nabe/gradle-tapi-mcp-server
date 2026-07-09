package com.example.gradle.mcp.cache

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleGuard
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope

internal fun buildCacheStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(),
            "includeLastMcpBuild" to booleanProperty("Last MCP build cache stats. Default true."),
            "includeLocalCacheDetails" to booleanProperty("Local cache directory summaries. Default true."),
            "includeDeclaredProperties" to booleanProperty("Cache keys from gradle.properties. Default true."),
            "probeConfigurationCache" to booleanProperty("Run configuration-cache compatibility probe. Default false."),
        ),
    )

context(runtime: GradleMcpRuntime)
fun Server.registerCacheTools(scope: CoroutineScope) {
    registerTool(
        scope,
        name = "gradle_get_build_cache_status",
        description = McpToolDescriptions.BUILD_CACHE_STATUS,
        schema = buildCacheStatusSchema(),
    ) { args ->
        val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
        val options = BuildCacheStatusOptions.fromArgs(args)
        ProjectLifecycleGuard.withNoActiveBuild(
            projectDirectory = projectDirectory,
            buildExecutionManager = runtime.buildExecutionManager,
            message = { directory ->
                "Cannot inspect build cache while a Gradle build is running for ${directory.path}. " +
                    "Wait for the build to finish or call gradle_get_build_status."
            },
        ) {
            val lastMcpBuild = if (options.includeLastMcpBuild) {
                runtime.buildExecutionManager.lastMcpBuildInsight(projectDirectory)
            } else {
                null
            }
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                jsonResult(
                    BuildCacheStatusCollector.collect(
                        connection = connection,
                        projectDirectory = projectDirectory,
                        options = options,
                        lastMcpBuild = lastMcpBuild,
                    ),
                )
            }
        }
    }
}
