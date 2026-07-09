package com.example.gradle.mcp.connection

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.model.ModelSerializers
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpToolDescriptions
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalProjectDirectoryProperty
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File

internal fun disconnectProjects(
    runtime: GradleMcpRuntime,
    projectDirectoryArg: String?,
): Map<String, Any?> {
    val projectDirectory = projectDirectoryArg?.let(ProjectDirectoryResolver::bestEffortDirectory)
    val hadActiveBuild = if (projectDirectory != null) {
        runtime.buildExecutionManager.hasActiveBuild(projectDirectory)
    } else {
        runtime.buildExecutionManager.hasActiveBuild()
    }
    val disconnected = synchronized(ProjectLifecycleLock) {
        runtime.buildExecutionManager.onDisconnect(projectDirectory)
        if (projectDirectory != null) {
            runtime.connectionManager.disconnect(projectDirectory)?.let { listOf(it) }.orEmpty()
        } else {
            runtime.connectionManager.disconnectAll()
        }
    }
    return buildMap {
        if (disconnected.isEmpty()) {
            put("state", "not_connected")
        } else if (disconnected.size == 1) {
            put("projectDirectory", disconnected.single().projectDirectory)
            put("state", disconnected.single().state)
        } else {
            put("state", "disconnected")
            put("projectDirectories", disconnected.map { it.projectDirectory })
        }
        if (hadActiveBuild) {
            put("warning", DISCONNECT_DURING_BUILD_WARNING)
        }
    }
}

private const val DISCONNECT_DURING_BUILD_WARNING =
    "One or more builds were still in progress for the disconnected project(s). The server cancelled them " +
        "via the Tooling API CancellationToken and marked status cancelled."

internal fun connectSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("projectDirectory"),
        properties = mapOf(
            "projectDirectory" to stringProperty("Gradle project root path"),
            "gradleUserHome" to stringProperty("Optional GRADLE_USER_HOME"),
            "gradleVersion" to stringProperty("Optional Gradle version to download"),
            "gradleInstallation" to stringProperty("Optional local Gradle installation"),
        ),
    )

internal fun connectionStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to optionalProjectDirectoryProperty(),
        ),
    )

internal fun buildEnvironmentSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(),
        ),
    )

internal fun disconnectSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to optionalProjectDirectoryProperty(),
        ),
    )

context(runtime: GradleMcpRuntime)
fun Server.registerConnectionTools(scope: CoroutineScope) {
    registerTool(
        scope,
        name = "gradle_connect",
        description = McpToolDescriptions.CONNECT,
        schema = connectSchema(),
    ) { args ->
        val projectDirectory = ProjectDirectoryResolver.canonicalDirectory(
            args.requiredString("projectDirectory"),
        )
        val config = ConnectionConfig(
            projectDirectory = projectDirectory.path,
            gradleUserHome = args.optionalString("gradleUserHome"),
            gradleVersion = args.optionalString("gradleVersion"),
            gradleInstallation = args.optionalString("gradleInstallation"),
        )
        val response = synchronized(ProjectLifecycleLock) {
            if (runtime.buildExecutionManager.hasActiveBuild(projectDirectory)) {
                throw McpException(
                    McpErrorCode.BUILD_ALREADY_RUNNING,
                    "Cannot connect while a Gradle build is running for ${projectDirectory.path}. " +
                        "Wait for the build to finish, call gradle_cancel_build, or call gradle_disconnect.",
                )
            }
            runtime.connectionManager.connect(config).toResponseMap()
        }
        jsonResult(response)
    }
    registerTool(
        scope,
        name = "gradle_connection_status",
        description = McpToolDescriptions.CONNECTION_STATUS,
        schema = connectionStatusSchema(),
    ) { args ->
        val projectDirectory = args.optionalString("projectDirectory")
            ?.let(ProjectDirectoryResolver::bestEffortDirectory)
        jsonResult(runtime.connectionManager.status(projectDirectory))
    }
    registerTool(
        scope,
        name = "gradle_disconnect",
        description = McpToolDescriptions.DISCONNECT,
        schema = disconnectSchema(),
    ) { args ->
        jsonResult(disconnectProjects(runtime, args.optionalString("projectDirectory")))
    }
    registerTool(
        scope,
        name = "gradle_get_build_environment",
        description = McpToolDescriptions.BUILD_ENVIRONMENT,
        schema = buildEnvironmentSchema(),
    ) { args ->
        val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
        runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
            val environment = connection.getModel(BuildEnvironment::class.java)
            jsonResult(ModelSerializers.buildEnvironment(environment))
        }
    }
}
