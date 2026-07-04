package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface McpBuildNotifier {
    fun notifyProgress(progress: Double, total: Double, message: String)

    fun notifyLog(message: String, level: LoggingLevel)
}

internal class ClientConnectionBuildNotifier(
    private val scope: CoroutineScope,
    private val connection: ClientConnection,
    private val progressToken: ProgressToken,
) : McpBuildNotifier {
    override fun notifyProgress(progress: Double, total: Double, message: String) {
        scope.launch {
            runCatching {
                connection.notification(
                    ProgressNotification(
                        ProgressNotificationParams(
                            progressToken = progressToken,
                            progress = progress,
                            total = total,
                            message = message,
                        ),
                    ),
                )
            }
        }
    }

    override fun notifyLog(message: String, level: LoggingLevel) {
        scope.launch {
            runCatching {
                connection.sendLoggingMessage(
                    LoggingMessageNotification(
                        LoggingMessageNotificationParams(
                            level = level,
                            logger = "gradle-build",
                            data = buildJsonObject { put("message", message) },
                        ),
                    ),
                )
            }
        }
    }
}

internal fun buildProgressNotifier(
    scope: CoroutineScope,
    connection: ClientConnection,
    progressToken: ProgressToken?,
): McpBuildNotifier? {
    if (progressToken == null) {
        return null
    }
    return ClientConnectionBuildNotifier(scope, connection, progressToken)
}
