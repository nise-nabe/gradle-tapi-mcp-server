package com.example.gradle.mcp.protocol

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class McpErrorsTest {
    @Test
    fun `maps mcp exception directly`() {
        mapExceptionToErrorCode(
            McpException(McpErrorCode.BUILD_ALREADY_RUNNING, "A Gradle build is already running for /tmp."),
        ) shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
    }

    @Test
    fun `maps illegal argument exception`() {
        mapExceptionToErrorCode(IllegalArgumentException("bad arg")) shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `maps bare illegal state to internal error even for legacy message shapes`() {
        // Agent-facing errors must be thrown as McpException; a bare
        // IllegalStateException reaching a handler is an internal bug.
        mapExceptionToErrorCode(
            IllegalStateException("Not connected to Gradle project: /tmp. Call gradle_connect first."),
        ) shouldBe McpErrorCode.INTERNAL_ERROR
    }

    @Test
    fun `maps unknown illegal state to internal error`() {
        mapExceptionToErrorCode(IllegalStateException("unexpected")) shouldBe McpErrorCode.INTERNAL_ERROR
    }

    @Test
    fun `maps generic exception to internal error`() {
        mapExceptionToErrorCode(RuntimeException("boom")) shouldBe McpErrorCode.INTERNAL_ERROR
    }

    @Test
    fun `structured error result returns JSON payload with isError true`() {
        val result = structuredErrorResult(McpErrorCode.NOT_CONNECTED, "Not connected")

        result.isError.shouldBeTrue()
        val text = (result.content.single() as TextContent).text
        text shouldContain "\"error\""
        val payload = decodeMcpJsonMap(text)
        payload["error"] shouldBe mapOf("code" to "NOT_CONNECTED", "message" to "Not connected")
    }

    @Test
    fun `structured error result merges error details into error object`() {
        val result = structuredErrorResult(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "Busy",
            errorDetails = mapOf(
                "activeBuildId" to "abc-123",
                "activeKind" to "tasks",
            ),
        )

        val payload = decodeMcpJsonMap((result.content.single() as TextContent).text)
        payload["error"] shouldBe mapOf(
            "code" to "BUILD_ALREADY_RUNNING",
            "message" to "Busy",
            "activeBuildId" to "abc-123",
            "activeKind" to "tasks",
        )
    }

    @Test
    fun `resource SDK exception keeps structured error data`() {
        val protocol = McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "Cannot query Gradle models while a build is active for /tmp.",
            errorDetails = mapOf("activeBuildId" to "running-build"),
        )

        val sdk = protocol.toSdkResourceException()

        sdk.code shouldBe RPCError.ErrorCode.INTERNAL_ERROR
        sdk.message shouldBe protocol.message
        val payload = decodeMcpJsonMap(sdk.data.toString())
        payload["error"] shouldBe mapOf(
            "code" to "BUILD_ALREADY_RUNNING",
            "message" to protocol.message,
            "activeBuildId" to "running-build",
        )
    }

    @Test
    fun `invalid argument resource errors use JSON-RPC invalid params`() {
        val sdk = McpException(McpErrorCode.INVALID_ARGUMENT, "bad uri").toSdkResourceException()
        sdk.code shouldBe RPCError.ErrorCode.INVALID_PARAMS
    }

    @Test
    fun `not connected resource errors use JSON-RPC resource not found`() {
        val sdk = McpException(McpErrorCode.NOT_CONNECTED, "Not connected").toSdkResourceException()
        sdk.code shouldBe RPCError.ErrorCode.RESOURCE_NOT_FOUND
        val payload = decodeMcpJsonMap(sdk.data.toString())
        payload["error"] shouldBe mapOf("code" to "NOT_CONNECTED", "message" to "Not connected")
    }
}
