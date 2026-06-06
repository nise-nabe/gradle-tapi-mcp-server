package com.example.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.spec.McpSchema

enum class McpErrorCode {
    NOT_CONNECTED,
    BUILD_ALREADY_RUNNING,
    INVALID_ARGUMENT,
    PROJECT_NOT_FOUND,
    BUILD_FAILED,
    INTERNAL_ERROR,
}

class McpException(
    val code: McpErrorCode,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private val errorMapper = jacksonObjectMapper()

fun structuredErrorResult(code: McpErrorCode, message: String): McpSchema.CallToolResult =
    McpSchema.CallToolResult(
        listOf(
            McpSchema.TextContent(
                errorMapper.writeValueAsString(
                    mapOf(
                        "error" to mapOf(
                            "code" to code.name,
                            "message" to message,
                        ),
                    ),
                ),
            ),
        ),
        true,
    )

fun mapExceptionToErrorCode(exception: Exception): McpErrorCode =
    when (exception) {
        is McpException -> exception.code
        is IllegalArgumentException -> McpErrorCode.INVALID_ARGUMENT
        is IllegalStateException -> {
            when {
                exception.message?.contains("Not connected", ignoreCase = true) == true ->
                    McpErrorCode.NOT_CONNECTED
                exception.message?.contains("already running", ignoreCase = true) == true ->
                    McpErrorCode.BUILD_ALREADY_RUNNING
                exception.message?.contains("does not exist", ignoreCase = true) == true ->
                    McpErrorCode.PROJECT_NOT_FOUND
                else -> McpErrorCode.INTERNAL_ERROR
            }
        }
        else -> McpErrorCode.INTERNAL_ERROR
    }
