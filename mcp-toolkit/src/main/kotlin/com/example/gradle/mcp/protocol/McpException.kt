package com.example.gradle.mcp.protocol

enum class McpErrorCode {
    NOT_CONNECTED,
    BUILD_ALREADY_RUNNING,
    BUILD_QUEUE_FULL,
    INVALID_ARGUMENT,
    PROJECT_NOT_FOUND,
    BUILD_FAILED,
    INTERNAL_ERROR,
}

class McpException(
    val code: McpErrorCode,
    override val message: String,
    cause: Throwable? = null,
    val errorDetails: Map<String, Any?> = emptyMap(),
) : Exception(message, cause)
