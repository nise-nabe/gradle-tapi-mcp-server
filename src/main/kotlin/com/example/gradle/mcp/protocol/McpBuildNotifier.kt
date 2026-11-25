package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

interface McpBuildNotifier {
    fun notifyProgress(progress: Double, total: Double, message: String)

    fun notifyLog(message: String, level: LoggingLevel)
}

internal class ClientConnectionBuildNotifier(
    private val scope: CoroutineScope,
    private val connection: ClientConnection,
    private val progressToken: ProgressToken,
) : McpBuildNotifier {
    private val orderedDispatcher = Dispatchers.Default.limitedParallelism(1)

    // Bound the notification backlog: a chatty build otherwise launches one
    // coroutine per log line with no ceiling. Progress updates drop silently
    // when saturated (the next update supersedes them); log lines apply
    // backpressure to the Gradle listener thread and are only dropped after
    // LOG_BACKPRESSURE_TIMEOUT_MS with a stuck or disconnected client.
    private val inFlight = Semaphore(MAX_PENDING_NOTIFICATIONS)

    override fun notifyProgress(progress: Double, total: Double, message: String) {
        if (!inFlight.tryAcquire()) {
            return
        }
        scope.launch(orderedDispatcher) {
            try {
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
            } finally {
                inFlight.release()
            }
        }
    }

    override fun notifyLog(message: String, level: LoggingLevel) {
        val acquired =
            try {
                inFlight.tryAcquire(LOG_BACKPRESSURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!acquired) {
            return
        }
        scope.launch(orderedDispatcher) {
            try {
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
            } finally {
                inFlight.release()
            }
        }
    }

    companion object {
        const val MAX_PENDING_NOTIFICATIONS: Int = 512
        const val LOG_BACKPRESSURE_TIMEOUT_MS: Long = 30_000L
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

internal fun resolveProgressToken(
    metaToken: ProgressToken?,
    args: Map<String, Any>,
): ProgressToken? {
    if (metaToken != null) {
        return metaToken
    }
    @Suppress("UNCHECKED_CAST")
    val nestedMeta = args["_meta"] as? Map<String, Any> ?: return null
    return toProgressToken(nestedMeta["progressToken"])
}

private fun toProgressToken(raw: Any?): ProgressToken? =
    when (raw) {
        is ProgressToken -> raw
        is String -> if (raw.isBlank()) null else ProgressToken(raw)
        is Number -> ProgressToken(raw.toLong())
        else -> null
    }
