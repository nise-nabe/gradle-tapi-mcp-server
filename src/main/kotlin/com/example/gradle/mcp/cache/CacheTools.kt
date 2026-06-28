package com.example.gradle.mcp.cache

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.projectDirectoryProperty
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures

internal fun buildCacheStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to projectDirectoryProperty(
                "Gradle project root to inspect. Defaults to GRADLE_PROJECT_DIR when set.",
            ),
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

context(runtime: GradleMcpRuntime)
fun cacheTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_get_build_cache_status",
            description = "Inspect Gradle build cache and configuration cache settings without a full build. Returns resolved cache properties (via properties -q), declared gradle.properties entries, local cache directory summaries, and optional last MCP build cache stats. Set probeConfigurationCache=true to run a lightweight configuration-cache compatibility check.",
            schema = buildCacheStatusSchema(),
        ) { args ->
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            if (runtime.buildExecutionManager.hasActiveBuild(projectDirectory)) {
                error(
                    "Cannot inspect build cache while a Gradle build is running for ${projectDirectory.path}. " +
                        "Wait for the build to finish or call gradle_get_build_status.",
                )
            }
            val options = BuildCacheStatusOptions.fromArgs(args)
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
        },
    )
