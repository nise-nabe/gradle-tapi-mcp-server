package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope

fun Server.registerTool(
    scope: CoroutineScope,
    name: String,
    description: String,
    schema: Map<String, Any>,
    handler: suspend ClientConnection.(Map<String, Any>) -> CallToolResult,
) {
    registerTool(scope, name, description, schema) { args, _ -> handler(args) }
}

fun Server.registerTool(
    scope: CoroutineScope,
    name: String,
    description: String,
    schema: Map<String, Any>,
    handler: suspend ClientConnection.(Map<String, Any>, McpBuildNotifier?) -> CallToolResult,
) {
    addTool(
        name = name,
        description = description,
        inputSchema = schema.toToolSchema(),
    ) { request ->
        try {
            val args = request.arguments.toToolArguments()
            val notifier = buildProgressNotifier(scope, this, request.meta?.progressToken)
            handler(args, notifier)
        } catch (exception: Exception) {
            val code = mapExceptionToErrorCode(exception)
            structuredErrorResult(code, exception.message ?: exception.toString())
        }
    }
}

fun jsonResult(value: Any?): CallToolResult =
    CallToolResult(
        content = listOf(
            TextContent(text = encodeMcpJsonDynamic(value)),
        ),
        isError = false,
    )
