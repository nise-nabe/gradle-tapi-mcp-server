package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import java.lang.reflect.Method

internal object McpProgressSupport {
    private val progressNotificationReflection: ProgressNotificationReflection? by lazy {
        runCatching {
            val notificationClass = Class.forName("io.modelcontextprotocol.spec.McpSchema\$ProgressNotification")
            val builderMethod = notificationClass.getMethod("builder")
            val builderClass = builderMethod.returnType
            ProgressNotificationReflection(
                builderMethod = builderMethod,
                progressTokenMethod = builderClass.getMethod("progressToken", Any::class.java),
                progressMethod = builderClass.getMethod("progress", java.lang.Double.TYPE),
                totalMethod = builderClass.getMethod("total", java.lang.Double.TYPE),
                messageMethod = builderClass.getMethod("message", String::class.java),
                buildMethod = builderClass.getMethod("build"),
                notificationClass = notificationClass,
                exchangeProgressMethod = McpSyncServerExchange::class.java.getMethod(
                    "progressNotification",
                    notificationClass,
                ),
            )
        }.getOrNull()
    }

    fun extractProgressToken(request: McpSchema.CallToolRequest): Any? =
        runCatching {
            val metaMethod = request.javaClass.methods.firstOrNull { method ->
                method.parameterCount == 0 && (method.name == "meta" || method.name == "getMeta")
            }
            val meta = metaMethod?.invoke(request) ?: return@runCatching nestedProgressToken(request)
            val tokenMethod = meta.javaClass.methods.firstOrNull { method ->
                method.parameterCount == 0 && (method.name == "progressToken" || method.name == "getProgressToken")
            }
            tokenMethod?.invoke(meta) ?: nestedProgressToken(request)
        }.getOrElse { nestedProgressToken(request) }

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
        val reflection = progressNotificationReflection ?: return
        runCatching {
            val builder = reflection.builderMethod.invoke(null)
            reflection.progressTokenMethod.invoke(builder, progressToken)
            reflection.progressMethod.invoke(builder, progress)
            reflection.totalMethod.invoke(builder, total)
            reflection.messageMethod.invoke(builder, message)
            val notification = reflection.buildMethod.invoke(builder)
            reflection.exchangeProgressMethod.invoke(exchange, notification)
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

    private data class ProgressNotificationReflection(
        val builderMethod: Method,
        val progressTokenMethod: Method,
        val progressMethod: Method,
        val totalMethod: Method,
        val messageMethod: Method,
        val buildMethod: Method,
        val notificationClass: Class<*>,
        val exchangeProgressMethod: Method,
    )
}
