package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

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
    McpServerFeatures.SyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(mcpJsonMapper, mcpObjectMapper().writeValueAsString(schema))
                .build(),
        )
        .callHandler { exchange, request ->
            try {
                handler(
                    exchange,
                    extractArgs(request),
                    McpProgressSupport.extractProgressToken(request),
                )
            } catch (exception: Exception) {
                val code = mapExceptionToErrorCode(exception)
                structuredErrorResult(code, exception.message ?: exception.toString())
            }
        }
        .build()

fun jsonResult(value: Any?): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
        .content(listOf(McpSchema.TextContent(mcpObjectMapper().writeValueAsString(value))))
        .isError(false)
        .build()

private fun extractArgs(request: McpSchema.CallToolRequest): Map<String, Any> {
    @Suppress("UNCHECKED_CAST")
    return request.arguments() as? Map<String, Any> ?: emptyMap()
}
