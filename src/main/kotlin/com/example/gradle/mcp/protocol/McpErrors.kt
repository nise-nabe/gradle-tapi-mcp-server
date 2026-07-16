package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

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
                exception.message?.startsWith("A Gradle build is already running") == true ||
                    exception.message?.startsWith("Cannot connect while a Gradle build is running") == true ||
                    exception.message?.startsWith("Cannot query Gradle models while a build is running") == true ||
                    exception.message?.startsWith("Cannot run prepareTasks while a Gradle build is running") == true ||
                    exception.message?.startsWith("Maximum concurrent builds") == true ->
                    McpErrorCode.BUILD_ALREADY_RUNNING
                exception.message?.startsWith("Build queue is full") == true ->
                    McpErrorCode.BUILD_QUEUE_FULL
                exception.message?.startsWith("Project directory does not exist:") == true ->
                    McpErrorCode.PROJECT_NOT_FOUND
                exception.message?.startsWith("Not connected to Gradle project:") == true ->
                    McpErrorCode.NOT_CONNECTED
                else -> McpErrorCode.INTERNAL_ERROR
            }
        }
        else -> McpErrorCode.INTERNAL_ERROR
    }
