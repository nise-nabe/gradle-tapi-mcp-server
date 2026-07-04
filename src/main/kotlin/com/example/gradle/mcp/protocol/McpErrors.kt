package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

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

fun structuredErrorResult(code: McpErrorCode, message: String): CallToolResult =
    CallToolResult(
        content = listOf(
            TextContent(
                text = encodeMcpJsonDynamic(
                    mapOf(
                        "error" to mapOf(
                            "code" to code.name,
                            "message" to message,
                        ),
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
        is IllegalStateException -> when (exception.message) {
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR." ->
                McpErrorCode.NOT_CONNECTED
            else -> when {
                exception.message?.startsWith("Another build is already running") == true ||
                    exception.message?.startsWith("Maximum concurrent background builds") == true ->
                    McpErrorCode.BUILD_ALREADY_RUNNING
                exception.message?.startsWith("Project directory does not exist:") == true ->
                    McpErrorCode.PROJECT_NOT_FOUND
                else -> McpErrorCode.INTERNAL_ERROR
            }
        }
        else -> McpErrorCode.INTERNAL_ERROR
    }
