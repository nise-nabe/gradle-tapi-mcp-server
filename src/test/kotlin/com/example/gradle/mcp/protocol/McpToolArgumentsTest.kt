package com.example.gradle.mcp.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class McpToolArgumentsTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "gradle_get_build_environment",
            "gradle_get_java_runtimes",
            "gradle_get_build_cache_status",
            "gradle_get_help",
            "gradle_get_gradle_build",
            "gradle_get_project_publications",
        ],
    )
    fun `rejectUnsupportedProjectPath rejects projectPath for unsupported tools`(toolName: String) {
        val error = shouldThrow<McpException> {
            rejectUnsupportedProjectPath(mapOf("projectPath" to ":plugin"), toolName)
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain toolName
    }

    @Test
    fun `rejectUnsupportedProjectPath allows blank projectPath`() {
        rejectUnsupportedProjectPath(mapOf("projectPath" to "   "), "gradle_get_gradle_build")
    }

    @Test
    fun `rejectUnsupportedProjectPath gradle build message references overview`() {
        val error = shouldThrow<McpException> {
            rejectUnsupportedProjectPath(mapOf("projectPath" to ":plugin"), "gradle_get_gradle_build")
        }

        error.message shouldContain "gradle_get_project_overview"
    }

    @Test
    fun `optionalPositiveInt accepts integral numbers and strings`() {
        mapOf("limit" to 5).optionalPositiveInt("limit") shouldBe 5
        mapOf("limit" to 5L).optionalPositiveInt("limit") shouldBe 5
        mapOf("limit" to "10").optionalPositiveInt("limit") shouldBe 10
    }

    @Test
    fun `optionalPositiveInt rejects non-integral and out-of-range numbers`() {
        mapOf("limit" to 1.9).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to 0).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to -3).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to Long.MAX_VALUE).optionalPositiveInt("limit") shouldBe null
        mapOf("limit" to "not-a-number").optionalPositiveInt("limit") shouldBe null
    }

    @Test
    fun `optionalNonNegativeInt accepts zero and rejects fractional values`() {
        mapOf("offset" to 0).optionalNonNegativeInt("offset") shouldBe 0
        mapOf("offset" to 2.5).optionalNonNegativeInt("offset") shouldBe null
        mapOf("offset" to -1).optionalNonNegativeInt("offset") shouldBe null
    }
}
