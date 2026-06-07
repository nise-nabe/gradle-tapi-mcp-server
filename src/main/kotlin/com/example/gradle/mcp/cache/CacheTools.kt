package com.example.gradle.mcp.cache

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures

internal fun buildCacheStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "includeLastMcpBuild" to booleanProperty(
                "Include task cache stats from the last MCP build run via gradle_run_tasks or gradle_run_tests (default true)",
            ),
            "includeLocalCacheDetails" to booleanProperty(
                "Include local build-cache and configuration-cache directory summaries (default true)",
            ),
            "includeDeclaredProperties" to booleanProperty(
                "Include cache-related entries from project and user gradle.properties files (default true)",
            ),
            "probeConfigurationCache" to booleanProperty(
                "Run properties -q --configuration-cache to test configuration-cache compatibility (default false)",
            ),
        ),
    )

context(connectionManager: GradleConnectionManager, buildExecutionManager: BuildExecutionManager)
fun cacheTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_get_build_cache_status",
            description = "Inspect Gradle build cache and configuration cache settings without a full build. Returns resolved cache properties (via properties -q), declared gradle.properties entries, local cache directory summaries, and optional last MCP build cache stats. Set probeConfigurationCache=true to run a lightweight configuration-cache compatibility check.",
            schema = buildCacheStatusSchema(),
        ) { args ->
            if (buildExecutionManager.hasActiveBuild()) {
                error(
                    "Cannot inspect build cache while a Gradle build is running. " +
                        "Wait for the build to finish or call gradle_get_build_status.",
                )
            }
            val options = BuildCacheStatusOptions.fromArgs(args)
            val projectDirectory = connectionManager.connectedProjectDirectory()
                ?: error("Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR.")
            val lastMcpBuild = if (options.includeLastMcpBuild) {
                buildExecutionManager.lastMcpBuildInsight(projectDirectory)
            } else {
                null
            }
            connectionManager.withConnectionResult { connection ->
                jsonResult(
                    BuildCacheStatusCollector.collect(
                        connection = connection,
                        projectDirectory = projectDirectory,
                        options = options,
                        lastMcpBuild = lastMcpBuild,
                    ),
                )
            }
        },
    )
