package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

fun structuredErrorResult(
    code: McpErrorCode,
    message: String,
    errorDetails: Map<String, Any?> = emptyMap(),
): CallToolResult =
    CallToolResult(
        content = listOf(
            TextContent(
                text = encodeMcpJsonDynamic(
                    mapOf(
                        "error" to buildMap {
                            put("code", code.name)
                            put("message", message)
                            putAll(errorDetails)
                        },
                    ),
                ),
            ),
        ),
        isError = true,
    )

fun mapExceptionToErrorCode(exception: Exception): McpErrorCode =
    when (exception) {
        is McpException -> exception.code
        is IllegalArgumentException -> McpErrorCode.INVALID_ARGUMENT
        // Agent-facing errors must be McpException with the right code at the
        // throw site; a bare IllegalStateException reaching a handler is a bug.
        else -> McpErrorCode.INTERNAL_ERROR
    }
