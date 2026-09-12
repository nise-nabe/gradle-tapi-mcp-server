package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.McpException as SdkMcpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError

internal const val MCP_RESOURCE_JSON_MIME_TYPE: String = GradleTapiResourceUri.JSON_MIME_TYPE

internal fun jsonResourceResult(uri: String, value: Any?): ReadResourceResult =
    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = encodeMcpJsonDynamic(value),
                uri = uri,
                mimeType = MCP_RESOURCE_JSON_MIME_TYPE,
            ),
        ),
    )

/**
 * Maps protocol errors onto the Kotlin MCP SDK exception the transport special-cases,
 * so `resources/read` is JSON-RPC `error` (not INTERNAL_ERROR with a bare message).
 * `data.error` matches tool `CallToolResult` error JSON (`code`, `message`, details).
 */
internal fun Exception.toSdkResourceException(): SdkMcpException {
    if (this is SdkMcpException) {
        return this
    }
    val protocol = this as? McpException
        ?: McpException(
            mapExceptionToErrorCode(this),
            message ?: toString(),
            this,
        )
    return protocol.toSdkResourceException()
}

internal fun McpException.toSdkResourceException(): SdkMcpException {
    val rpcCode = when (code) {
        McpErrorCode.INVALID_ARGUMENT -> RPCError.ErrorCode.INVALID_PARAMS
        McpErrorCode.NOT_CONNECTED,
        McpErrorCode.PROJECT_NOT_FOUND,
        -> RPCError.ErrorCode.RESOURCE_NOT_FOUND
        else -> RPCError.ErrorCode.INTERNAL_ERROR
    }
    val payload = mapOf(
        "error" to buildMap {
            put("code", code.name)
            put("message", message)
            putAll(errorDetails)
        },
    )
    return SdkMcpException(rpcCode, message, payload.toJsonElement(), this)
}
