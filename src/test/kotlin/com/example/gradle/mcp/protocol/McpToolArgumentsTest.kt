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
}
