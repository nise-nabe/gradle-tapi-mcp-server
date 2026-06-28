package com.example.gradle.mcp.connection

import com.example.gradle.mcp.GradleMcpRuntime
import com.example.gradle.mcp.model.HelpLimitOptions
import com.example.gradle.mcp.model.ModelSerializers
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.booleanProperty
import com.example.gradle.mcp.protocol.emptyObjectSchema
import com.example.gradle.mcp.protocol.integerProperty
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.projectDirectoryProperty
import com.example.gradle.mcp.protocol.resolveRequiredProjectDirectoryProperty
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.UnknownModelException
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.Help
import java.io.File

private const val DISCONNECT_DURING_BUILD_WARNING =
    "One or more builds were still in progress for the disconnected project(s). The server cancelled them " +
        "via the Tooling API CancellationToken and marked status cancelled."

internal fun helpSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "maxChars" to integerProperty(
                "Maximum rendered help characters to return (default ${HelpLimitOptions.DEFAULT_MAX_CHARS})",
            ),
            "tailOutput" to booleanProperty(
                "When truncated, keep the tail of the help text (default true)",
            ),
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(
                "Gradle project root to query.",
            ),
        ),
    )

private fun fetchHelpModel(connection: ProjectConnection): Help =
    try {
        connection.getModel(Help::class.java)
    } catch (exception: Exception) {
        if (exception is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        when (exception) {
            is UnknownModelException, is UnsupportedVersionException -> throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "Help model is not available. Gradle 9.4 or later is required.",
                exception,
            )
            else -> throw exception
        }
    }

internal fun connectSchema(): Map<String, Any> =
    objectSchema(
        required = listOf("projectDirectory"),
        properties = mapOf(
            "projectDirectory" to stringProperty("Absolute or relative path to the Gradle project root"),
            "gradleUserHome" to stringProperty("Optional GRADLE_USER_HOME directory"),
            "gradleVersion" to stringProperty("Optional Gradle version to download and use"),
            "gradleInstallation" to stringProperty("Optional local Gradle installation directory"),
        ),
    )

private fun connectionStatusSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to projectDirectoryProperty(
                "Gradle project root to inspect. Omit to list all active connections plus the default project.",
            ),
        ),
    )

private fun buildEnvironmentSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to resolveRequiredProjectDirectoryProperty(
                "Gradle project root to query.",
            ),
        ),
    )

private fun disconnectSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "projectDirectory" to projectDirectoryProperty(
                "Gradle project root to disconnect. Omit to disconnect all active connections.",
            ),
        ),
    )

context(runtime: GradleMcpRuntime)
fun connectionTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_connect",
            description = "Connect to a Gradle project via the Tooling API without disconnecting other projects. Call when gradle_connection_status shows the target is not connected, or to register an additional project. Rejects the call while a build is running for the same projectDirectory. Reuses an existing compatible daemon when available.",
            schema = connectSchema(),
        ) { args ->
            val projectDirectory = ProjectDirectoryResolver.canonicalDirectory(
                args.requiredString("projectDirectory"),
            )
            if (runtime.buildExecutionManager.hasActiveBuild(projectDirectory)) {
                error(
                    "Cannot connect while a Gradle build is running for ${projectDirectory.path}. " +
                        "Wait for the build to finish, call gradle_cancel_build, or call gradle_disconnect.",
                )
            }
            val config = ConnectionConfig(
                projectDirectory = projectDirectory.path,
                gradleUserHome = args.optionalString("gradleUserHome"),
                gradleVersion = args.optionalString("gradleVersion"),
                gradleInstallation = args.optionalString("gradleInstallation"),
            )
            jsonResult(runtime.connectionManager.connect(config))
        },
        tool(
            name = "gradle_connection_status",
            description = "Return Tooling API connection status. Omit projectDirectory to list every active connection (connections[]) plus defaultProjectDirectory and legacy flat fields for the default project. With projectDirectory, return status for that project only. When GRADLE_PROJECT_DIR is set, the server auto-connects that project on startup. Use gradle_get_build_environment for a fresh query including gradleUserHome and jvmArguments.",
            schema = connectionStatusSchema(),
        ) { args ->
            val projectDirectory = args.optionalString("projectDirectory")
                ?.let(ProjectDirectoryResolver::bestEffortDirectory)
            jsonResult(runtime.connectionManager.status(projectDirectory))
        },
        tool(
            name = "gradle_disconnect",
            description = "Close one or all Tooling API project connections. Omit projectDirectory to disconnect all projects. Running builds for the disconnected project(s) are cancelled via the Tooling API CancellationToken.",
            schema = disconnectSchema(),
        ) { args ->
            val projectDirectory = args.optionalString("projectDirectory")
                ?.let(ProjectDirectoryResolver::bestEffortDirectory)
            val hadActiveBuild = if (projectDirectory != null) {
                runtime.buildExecutionManager.hasActiveBuild(projectDirectory)
            } else {
                runtime.buildExecutionManager.hasActiveBuild()
            }
            val disconnected = try {
                if (projectDirectory != null) {
                    runtime.connectionManager.disconnect(projectDirectory)?.let { listOf(it) }.orEmpty()
                } else {
                    runtime.connectionManager.disconnectAll()
                }
            } finally {
                runtime.buildExecutionManager.onDisconnect(projectDirectory)
            }
            val payload = buildMap<String, Any?> {
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
            jsonResult(payload)
        },
        tool(
            name = "gradle_get_build_environment",
            description = "Fetch BuildEnvironment (Gradle version, Gradle user home, Java home, versionInfo). versionInfo is the gradle --version output when the connected Gradle is 9.4+; omitted on older Gradle. Lightweight; prefer this over project model for stack checks.",
            schema = buildEnvironmentSchema(),
        ) { args ->
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val environment = connection.getModel(BuildEnvironment::class.java)
                jsonResult(ModelSerializers.buildEnvironment(environment))
            }
        },
        tool(
            name = "gradle_get_help",
            description = "Fetch Gradle CLI help text (equivalent to `gradle --help`). Requires Gradle 9.4+; returns a structured error if the Help model is unavailable.",
            schema = helpSchema(),
        ) { args ->
            val limitOptions = HelpLimitOptions.fromArgs(args)
            val projectDirectory = ProjectDirectoryResolver.resolveRequired(args, runtime.connectionManager)
            runtime.connectionManager.withConnectionResult(projectDirectory) { connection ->
                val help = fetchHelpModel(connection)
                jsonResult(ModelSerializers.help(help, limitOptions))
            }
        },
    )
