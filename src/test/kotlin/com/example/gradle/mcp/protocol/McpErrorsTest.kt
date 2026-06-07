package com.example.gradle.mcp.protocol

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue

class McpErrorsTest {
    @Test
    fun `maps not connected state exception`() {
        val code = mapExceptionToErrorCode(
            IllegalStateException(
                "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR.",
            ),
        )

        code shouldBe McpErrorCode.NOT_CONNECTED
    }

    @Test
    fun `maps build already running exception`() {
        val code = mapExceptionToErrorCode(
            McpException(McpErrorCode.BUILD_ALREADY_RUNNING, "Another build is already running"),
        )

        code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
    }

    @Test
    fun `maps missing project directory to project not found`() {
        val code = mapExceptionToErrorCode(
            McpException(McpErrorCode.PROJECT_NOT_FOUND, "Project directory does not exist: /missing"),
        )

        code shouldBe McpErrorCode.PROJECT_NOT_FOUND
    }

    @Test
    fun `structured error result returns JSON payload with isError true`() {
        val result = structuredErrorResult(McpErrorCode.NOT_CONNECTED, "Not connected")

        result.isError.shouldBeTrue()
        val text = (result.content.single() as McpSchema.TextContent).text
        val payload = mcpObjectMapper().readValue<StructuredErrorPayload>(text)
        payload.error.code shouldBe "NOT_CONNECTED"
        payload.error.message shouldBe "Not connected"
    }
}

private data class StructuredErrorPayload(val error: StructuredErrorDetail)

private data class StructuredErrorDetail(val code: String, val message: String)
