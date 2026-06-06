package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpErrorsTest {
    @Test
    fun `maps not connected state exception`() {
        val code = mapExceptionToErrorCode(
            IllegalStateException("Not connected to a Gradle project. Call gradle_connect first."),
        )

        assertEquals(McpErrorCode.NOT_CONNECTED, code)
    }

    @Test
    fun `maps build already running exception`() {
        val code = mapExceptionToErrorCode(
            McpException(McpErrorCode.BUILD_ALREADY_RUNNING, "Another build is already running"),
        )

        assertEquals(McpErrorCode.BUILD_ALREADY_RUNNING, code)
    }

    @Test
    fun `maps missing project directory to project not found`() {
        val code = mapExceptionToErrorCode(
            McpException(McpErrorCode.PROJECT_NOT_FOUND, "Project directory does not exist: /missing"),
        )

        assertEquals(McpErrorCode.PROJECT_NOT_FOUND, code)
    }
}
