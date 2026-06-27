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
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.UnknownModelException
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.Help

private const val DISCONNECT_DURING_BUILD_WARNING =
    "One or more builds were still in progress. The server cancelled them via the Tooling API " +
        "CancellationToken and marked status cancelled."

internal fun helpSchema(): Map<String, Any> =
    objectSchema(
        properties = mapOf(
            "maxChars" to integerProperty(
                "Maximum rendered help characters to return (default ${HelpLimitOptions.DEFAULT_MAX_CHARS})",
            ),
            "tailOutput" to booleanProperty(
                "When truncated, keep the tail of the help text (default true)",
            ),
        ),
    )

private fun fetchHelpModel(connection: ProjectConnection): Help =
    try {
        connection.getModel(Help::class.java)
    } catch (exception: Exception) {
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

context(runtime: GradleMcpRuntime)
fun connectionTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_connect",
            description = "Connect to a Gradle project via the Tooling API. Call when gradle_connection_status.connected=false or to switch projects; skip when GRADLE_PROJECT_DIR auto-connect already left the server connected. When no build is running, cancels any running builds before connecting; rejects the call while builds are still running. Reuses an existing compatible daemon when available.",
            schema = connectSchema(),
        ) { args ->
            if (runtime.buildExecutionManager.hasActiveBuild()) {
                error(
                    "Cannot connect while a Gradle build is running. " +
                        "Wait for the build to finish, call gradle_cancel_build, or call gradle_disconnect.",
                )
            }
            runtime.buildExecutionManager.resetBuildState("Preparing new Gradle connection")
            val config = ConnectionConfig(
                projectDirectory = args.requiredString("projectDirectory"),
                gradleUserHome = args.optionalString("gradleUserHome"),
                gradleVersion = args.optionalString("gradleVersion"),
                gradleInstallation = args.optionalString("gradleInstallation"),
            )
            jsonResult(runtime.connectionManager.connect(config))
        },
        tool(
            name = "gradle_connection_status",
            description = "Return the current Tooling API connection status and a connect-time runtime stack snapshot (gradleVersion, javaHome, javaVersion). Prefer calling this first: when GRADLE_PROJECT_DIR is set in the MCP server env (for example \${workspaceFolder} in Cursor mcp.json), the server auto-connects on startup so other Gradle tools work without gradle_connect. Auto-connect is best-effort, adds startup time (~1-2s with a warm daemon; longer on cold or large projects), and omit GRADLE_PROJECT_DIR when faster MCP startup matters. Stack fields are null with runtimeStackAvailable=false when the snapshot could not be loaded at connect. If connected=false, call gradle_connect or set GRADLE_PROJECT_DIR and restart MCP. For a fresh query including gradleUserHome and jvmArguments, use gradle_get_build_environment.",
            schema = emptyObjectSchema(),
        ) { _ ->
            jsonResult(runtime.connectionManager.status())
        },
        tool(
            name = "gradle_disconnect",
            description = "Close the active Tooling API project connection. If builds are still running, the server cancels them via the Tooling API CancellationToken and marks status cancelled. A warning field is included when builds were active.",
            schema = emptyObjectSchema(),
        ) { _ ->
            val hadActiveBuild = runtime.buildExecutionManager.hasActiveBuild()
            val disconnected = try {
                runtime.connectionManager.disconnect()
            } finally {
                runtime.buildExecutionManager.onDisconnect()
            }
            val payload = buildMap<String, Any?> {
                if (disconnected != null) {
                    put("projectDirectory", disconnected.projectDirectory)
                    put("state", disconnected.state)
                } else {
                    put("state", "not_connected")
                }
                if (hadActiveBuild) {
                    put("warning", DISCONNECT_DURING_BUILD_WARNING)
                }
            }
            jsonResult(payload)
        },
        tool(
            name = "gradle_get_build_environment",
            description = "Fetch BuildEnvironment (Gradle version, Gradle user home, Java home). Lightweight; prefer this over project model for stack checks.",
            schema = emptyObjectSchema(),
        ) { _ ->
            runtime.connectionManager.withConnectionResult { connection ->
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
            runtime.connectionManager.withConnectionResult { connection ->
                val help = fetchHelpModel(connection)
                jsonResult(ModelSerializers.help(help, limitOptions))
            }
        },
    )
