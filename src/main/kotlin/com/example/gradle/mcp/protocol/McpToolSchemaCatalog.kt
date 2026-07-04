package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.buildStatusSchema
import com.example.gradle.mcp.build.cancelBuildSchema
import com.example.gradle.mcp.build.listBuildsSchema
import com.example.gradle.mcp.build.runTasksSchema
import com.example.gradle.mcp.build.runTestsSchema
import com.example.gradle.mcp.cache.buildCacheStatusSchema
import com.example.gradle.mcp.connection.buildEnvironmentSchema
import com.example.gradle.mcp.connection.connectSchema
import com.example.gradle.mcp.connection.connectionStatusSchema
import com.example.gradle.mcp.connection.disconnectSchema
import com.example.gradle.mcp.connection.javaRuntimesSchema
import com.example.gradle.mcp.model.buildInvocationsQuerySchema
import com.example.gradle.mcp.model.helpSchema
import com.example.gradle.mcp.model.modelQuerySchema
import com.example.gradle.mcp.model.projectTreeSchema
import com.example.gradle.mcp.model.publicationsSchema

internal data class McpToolSpec(
    val name: String,
    val description: String,
    val schema: Map<String, Any>,
)

internal fun allMcpToolSpecs(): List<McpToolSpec> =
    listOf(
        McpToolSpec("gradle_connect", McpToolDescriptions.CONNECT, connectSchema()),
        McpToolSpec("gradle_connection_status", McpToolDescriptions.CONNECTION_STATUS, connectionStatusSchema()),
        McpToolSpec("gradle_disconnect", McpToolDescriptions.DISCONNECT, disconnectSchema()),
        McpToolSpec("gradle_get_build_environment", McpToolDescriptions.BUILD_ENVIRONMENT, buildEnvironmentSchema()),
        McpToolSpec("gradle_get_java_runtimes", McpToolDescriptions.JAVA_RUNTIMES, javaRuntimesSchema()),
        McpToolSpec("gradle_get_build_cache_status", McpToolDescriptions.BUILD_CACHE_STATUS, buildCacheStatusSchema()),
        McpToolSpec("gradle_get_project_overview", McpToolDescriptions.PROJECT_OVERVIEW, projectTreeSchema()),
        McpToolSpec("gradle_get_gradle_build", McpToolDescriptions.GRADLE_BUILD, projectTreeSchema()),
        McpToolSpec("gradle_get_project_model", McpToolDescriptions.PROJECT_MODEL, modelQuerySchema()),
        McpToolSpec("gradle_get_build_invocations", McpToolDescriptions.BUILD_INVOCATIONS, buildInvocationsQuerySchema()),
        McpToolSpec("gradle_get_project_publications", McpToolDescriptions.PROJECT_PUBLICATIONS, publicationsSchema()),
        McpToolSpec("gradle_get_help", McpToolDescriptions.HELP, helpSchema()),
        McpToolSpec("gradle_list_builds", McpToolDescriptions.LIST_BUILDS, listBuildsSchema()),
        McpToolSpec("gradle_cancel_build", McpToolDescriptions.CANCEL_BUILD, cancelBuildSchema()),
        McpToolSpec("gradle_get_build_status", McpToolDescriptions.BUILD_STATUS, buildStatusSchema()),
        McpToolSpec("gradle_run_tasks", McpToolDescriptions.RUN_TASKS, runTasksSchema()),
        McpToolSpec("gradle_run_tests", McpToolDescriptions.RUN_TESTS, runTestsSchema()),
    )
