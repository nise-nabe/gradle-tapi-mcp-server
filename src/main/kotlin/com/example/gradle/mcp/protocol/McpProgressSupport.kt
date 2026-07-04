package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

internal object McpProgressSupport {
    fun extractProgressToken(request: McpSchema.CallToolRequest): Any? {
        val meta = request.meta()
        val token = meta?.get("progressToken")
        return token ?: nestedProgressToken(request)
    }

    fun sendProgress(
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
        progress: Double,
        total: Double,
        message: String,
    ) {
        if (exchange == null || progressToken == null) {
            return
        }
        runCatching {
            exchange.progressNotification(
                McpSchema.ProgressNotification.builder(progressToken, progress)
                    .total(total)
                    .message(message)
                    .build(),
            )
        }
    }

    fun sendLog(
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
        message: String,
        level: McpSchema.LoggingLevel,
    ) {
        if (exchange == null || progressToken == null) {
            return
        }
        runCatching {
            exchange.loggingNotification(
                McpSchema.LoggingMessageNotification.builder(level, message)
                    .logger("gradle-build")
                    .build(),
            )
        }
    }

    private fun nestedProgressToken(request: McpSchema.CallToolRequest): Any? {
        val args = request.arguments()
        @Suppress("UNCHECKED_CAST")
        val nestedMeta = args["_meta"] as? Map<String, Any>
        return nestedMeta?.get("progressToken")
    }
}
