package com.example.gradle.mcp.connection

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.model.ModelSerializers
import com.example.gradle.mcp.protocol.emptyObjectSchema
import com.example.gradle.mcp.protocol.jsonResult
import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.optionalString
import com.example.gradle.mcp.protocol.requiredString
import com.example.gradle.mcp.protocol.stringProperty
import com.example.gradle.mcp.protocol.tool
import io.modelcontextprotocol.server.McpServerFeatures
import org.gradle.tooling.model.build.BuildEnvironment

private const val DISCONNECT_DURING_BUILD_WARNING =
    "A build was still in progress. The server released its build slot immediately, but the Gradle " +
        "daemon may briefly run overlapping Tooling API work until the previous call unwinds."

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

context(connectionManager: GradleConnectionManager, buildExecutionManager: BuildExecutionManager)
fun connectionTools(): List<McpServerFeatures.SyncToolSpecification> =
    listOf(
        tool(
            name = "gradle_connect",
            description = "Connect to a Gradle project via the Tooling API. When no build is running, resets the active build slot and marks any running builds as failed before connecting; rejects the call while a build slot is held. Reuses an existing compatible daemon when available.",
            schema = connectSchema(),
        ) { args ->
            if (buildExecutionManager.hasActiveBuild()) {
                error(
                    "Cannot connect while a Gradle build is running. " +
                        "Wait for the build to finish or call gradle_disconnect.",
                )
            }
            buildExecutionManager.resetBuildState("Preparing new Gradle connection")
            val config = ConnectionConfig(
                projectDirectory = args.requiredString("projectDirectory"),
                gradleUserHome = args.optionalString("gradleUserHome"),
                gradleVersion = args.optionalString("gradleVersion"),
                gradleInstallation = args.optionalString("gradleInstallation"),
            )
            jsonResult(connectionManager.connect(config))
        },
        tool(
            name = "gradle_connection_status",
            description = "Return the current Tooling API connection status and a connect-time runtime stack snapshot (gradleVersion, javaHome, javaVersion). Stack fields are null with runtimeStackAvailable=false when the snapshot could not be loaded at connect. For a fresh query including gradleUserHome and jvmArguments, use gradle_get_build_environment.",
            schema = emptyObjectSchema(),
        ) { _ ->
            jsonResult(connectionManager.status())
        },
        tool(
            name = "gradle_disconnect",
            description = "Close the active Tooling API project connection. If a build is still running, the server releases its build slot immediately; the Gradle daemon may briefly continue the prior Tooling API call until it unwinds. A warning field is included when a build was active.",
            schema = emptyObjectSchema(),
        ) { _ ->
            val hadActiveBuild = buildExecutionManager.hasActiveBuild()
            val disconnected = try {
                connectionManager.disconnect()
            } finally {
                buildExecutionManager.onDisconnect()
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
            connectionManager.withConnectionResult { connection ->
                val environment = connection.getModel(BuildEnvironment::class.java)
                jsonResult(ModelSerializers.buildEnvironment(environment))
            }
        },
    )
