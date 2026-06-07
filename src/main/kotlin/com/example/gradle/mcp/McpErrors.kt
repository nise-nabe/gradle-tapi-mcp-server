package com.example.gradle.mcp

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
        is IllegalStateException -> when (exception.message) {
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR." ->
                McpErrorCode.NOT_CONNECTED
            else -> when {
                exception.message?.startsWith("Another build is already running") == true ->
                    McpErrorCode.BUILD_ALREADY_RUNNING
                exception.message?.startsWith("Project directory does not exist:") == true ->
                    McpErrorCode.PROJECT_NOT_FOUND
                else -> McpErrorCode.INTERNAL_ERROR
            }
        }
        else -> McpErrorCode.INTERNAL_ERROR
    }
