package com.example.gradle.mcp.protocol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

private val toolObjectMapper = jacksonObjectMapper()

fun tool(
    name: String,
    description: String,
    schema: Map<String, Any>,
    handler: (Map<String, Any>) -> McpSchema.CallToolResult,
): McpServerFeatures.SyncToolSpecification =
    tool(name, description, schema) { _, args, _ -> handler(args) }

fun tool(
    name: String,
    description: String,
    schema: Map<String, Any>,
    handler: (McpSyncServerExchange, Map<String, Any>, Any?) -> McpSchema.CallToolResult,
): McpServerFeatures.SyncToolSpecification =
    McpServerFeatures.SyncToolSpecification(
        McpSchema.Tool(name, description, toolObjectMapper.writeValueAsString(schema)),
    ) { exchange, requestOrArgs ->
        try {
            handler(
                exchange,
                extractArgs(requestOrArgs),
                extractProgressToken(requestOrArgs),
            )
        } catch (exception: Exception) {
            val code = mapExceptionToErrorCode(exception)
            structuredErrorResult(code, exception.message ?: exception.toString())
        }
    }

fun jsonResult(value: Any?): McpSchema.CallToolResult =
    McpSchema.CallToolResult(
        listOf(McpSchema.TextContent(toolObjectMapper.writeValueAsString(value))),
        false,
    )

private fun extractArgs(requestOrArgs: Any): Map<String, Any> {
    if (requestOrArgs is McpSchema.CallToolRequest) {
        @Suppress("UNCHECKED_CAST")
        return requestOrArgs.arguments() as? Map<String, Any> ?: emptyMap()
    }
    @Suppress("UNCHECKED_CAST")
    return requestOrArgs as? Map<String, Any> ?: emptyMap()
}

private fun extractProgressToken(requestOrArgs: Any): Any? {
    if (requestOrArgs is McpSchema.CallToolRequest) {
        return McpProgressSupport.extractProgressToken(requestOrArgs)
    }
    @Suppress("UNCHECKED_CAST")
    val args = requestOrArgs as? Map<String, Any> ?: return null
    @Suppress("UNCHECKED_CAST")
    val nestedMeta = args["_meta"] as? Map<String, Any>
    return nestedMeta?.get("progressToken")
}
