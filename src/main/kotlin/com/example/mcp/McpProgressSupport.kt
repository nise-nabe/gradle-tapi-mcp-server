package com.example.mcp

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

internal object McpProgressSupport {
    fun extractProgressToken(request: McpSchema.CallToolRequest): Any? {
        val metaMethod = request.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 0 && (method.name == "meta" || method.name == "getMeta")
        }
        val meta = metaMethod?.invoke(request) ?: return nestedProgressToken(request)
        val tokenMethod = meta.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 0 && (method.name == "progressToken" || method.name == "getProgressToken")
        }
        return tokenMethod?.invoke(meta) ?: nestedProgressToken(request)
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
            val notificationClass = Class.forName("io.modelcontextprotocol.spec.McpSchema\$ProgressNotification")
            val builder = notificationClass.getMethod("builder").invoke(null)
            val builderClass = builder.javaClass
            builderClass.getMethod("progressToken", Any::class.java).invoke(builder, progressToken)
            builderClass.getMethod("progress", java.lang.Double.TYPE).invoke(builder, progress)
            builderClass.getMethod("total", java.lang.Double.TYPE).invoke(builder, total)
            builderClass.getMethod("message", String::class.java).invoke(builder, message)
            val notification = builderClass.getMethod("build").invoke(builder)
            exchange.javaClass.getMethod(
                "progressNotification",
                notificationClass,
            ).invoke(exchange, notification)
        }
    }

    fun sendLog(exchange: McpSyncServerExchange?, message: String, level: McpSchema.LoggingLevel) {
        if (exchange == null) {
            return
        }
        runCatching {
            exchange.loggingNotification(
                McpSchema.LoggingMessageNotification.builder()
                    .level(level)
                    .logger("gradle-build")
                    .data(message)
                    .build(),
            )
        }
    }

    private fun nestedProgressToken(request: McpSchema.CallToolRequest): Any? {
        @Suppress("UNCHECKED_CAST")
        val args = request.arguments() as? Map<String, Any> ?: return null
        @Suppress("UNCHECKED_CAST")
        val nestedMeta = args["_meta"] as? Map<String, Any>
        return nestedMeta?.get("progressToken")
    }
}
