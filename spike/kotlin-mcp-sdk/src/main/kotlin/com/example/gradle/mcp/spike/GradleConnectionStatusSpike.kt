package com.example.gradle.mcp.spike

import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal Kotlin MCP SDK spike: stdio transport, one read-only tool, progress + logging notifications.
 * Does not use Gradle Tooling API; validates SDK wiring only.
 */
fun main() {
    val server = Server(
        serverInfo = Implementation(
            name = "gradle-tapi-mcp-kotlin-sdk-spike",
            version = "0.1.0-spike",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(),
                logging = ServerCapabilities.Logging,
            ),
        ),
    )

    server.addTool(
        name = "gradle_connection_status",
        description = "Spike tool: returns a static connection-status payload (no Tooling API).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("projectDirectory") {
                    put("type", "string")
                    put(
                        "description",
                        "Gradle project root to inspect. Omit to list all active connections.",
                    )
                }
            },
        ),
    ) { request ->
        val projectDirectory = request.arguments?.get("projectDirectory")?.toString()?.trim('"')
        val payload = buildJsonObject {
            put("connected", false)
            put("spike", true)
            put("message", "Kotlin MCP SDK spike response")
            if (!projectDirectory.isNullOrBlank()) {
                put("projectDirectory", projectDirectory)
            }
        }.toString()
        CallToolResult(content = listOf(TextContent(text = payload)))
    }

    server.addTool(
        name = "spike_progress_demo",
        description = "Spike tool: emits progress and logging notifications when progressToken is present in _meta.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) { request ->
        val progressToken = request.meta?.get("progressToken")?.let(::toProgressToken)
        if (progressToken != null) {
            sendLoggingMessage(
                LoggingMessageNotification(
                    LoggingMessageNotificationParams(
                        level = LoggingLevel.Info,
                        logger = "gradle-build",
                        data = buildJsonObject { put("message", "Kotlin SDK spike log") },
                    ),
                ),
            )
            notification(
                ProgressNotification(
                    ProgressNotificationParams(
                        progressToken = progressToken,
                        progress = 1.0,
                        total = 1.0,
                        message = "Kotlin SDK spike progress",
                    ),
                ),
            )
        }
        CallToolResult(content = listOf(TextContent(text = """{"ok":true,"progressSent":${progressToken != null}}""")))
    }

    val transport = StdioServerTransport(
        inputStream = System.`in`.asInput(),
        outputStream = System.out.asSink().buffered(),
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}

private fun toProgressToken(element: kotlinx.serialization.json.JsonElement): ProgressToken? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) {
        return ProgressToken(primitive.content)
    }
    primitive.longOrNull?.let { return ProgressToken(it) }
    return null
}
