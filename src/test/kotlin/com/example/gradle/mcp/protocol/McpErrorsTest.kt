package com.example.gradle.mcp.protocol

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

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

    @ParameterizedTest
    @MethodSource("buildAlreadyRunningMessages")
    fun `maps build already running messages`(message: String) {
        mapExceptionToErrorCode(IllegalStateException(message)) shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
    }

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
    fun `maps project not found legacy message`() {
        mapExceptionToErrorCode(
            IllegalStateException("Project directory does not exist: /missing"),
        ) shouldBe McpErrorCode.PROJECT_NOT_FOUND
    }

    @Test
    fun `maps per project not connected message`() {
        mapExceptionToErrorCode(
            IllegalStateException("Not connected to Gradle project: /tmp. Call gradle_connect first."),
        ) shouldBe McpErrorCode.NOT_CONNECTED
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

    companion object {
        @JvmStatic
        fun buildAlreadyRunningMessages(): Stream<Arguments> =
            Stream.of(
                Arguments.of("A Gradle build is already running for /tmp."),
                Arguments.of("Cannot connect while a Gradle build is running for /tmp."),
                Arguments.of("Cannot query Gradle models while a build is running for /tmp."),
                Arguments.of("Cannot run prepareTasks while a Gradle build is running for /tmp."),
                Arguments.of("Maximum concurrent builds (4) reached. Poll gradle_get_build_status."),
            )
    }
}
