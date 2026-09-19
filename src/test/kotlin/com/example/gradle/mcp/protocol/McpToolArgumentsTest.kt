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
    fun `rejectUnsupportedBuildTreePath rejects buildTreePath for unsupported tools`() {
        val error = shouldThrow<McpException> {
            rejectUnsupportedBuildTreePath(mapOf("buildTreePath" to ":buildSrc"), "gradle_get_gradle_build")
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "gradle_get_gradle_build"
        error.message shouldContain "gradle_get_project_overview"
    }

    @Test
    fun `rejectUnsupportedBuildTreePath allows blank buildTreePath`() {
        rejectUnsupportedBuildTreePath(mapOf("buildTreePath" to "   "), "gradle_get_gradle_build")
    }

    @Test
    fun `optionalStringList returns string lists and null when absent`() {
        emptyMap<String, Any>().optionalStringList("tasks") shouldBe null
        mapOf("tasks" to listOf("build", "test")).optionalStringList("tasks") shouldBe
            listOf("build", "test")
        mapOf("tasks" to emptyList<String>()).optionalStringList("tasks") shouldBe emptyList()
    }

    @Test
    fun `optionalStringList rejects non-list values and non-string elements`() {
        val nonList = shouldThrow<McpException> {
            mapOf("tasks" to "build").optionalStringList("tasks")
        }
        nonList.code shouldBe McpErrorCode.INVALID_ARGUMENT
        nonList.message shouldContain "tasks"

        val mixed = shouldThrow<McpException> {
            mapOf("tasks" to listOf("build", 42)).optionalStringList("tasks")
        }
        mixed.code shouldBe McpErrorCode.INVALID_ARGUMENT
        mixed.message shouldContain "tasks"
    }

    @Test
    fun `optionalBoolean applies default only when absent`() {
        emptyMap<String, Any>().optionalBoolean("flag", default = true) shouldBe true
        mapOf("flag" to false).optionalBoolean("flag", default = true) shouldBe false
        mapOf("flag" to true).optionalBoolean("flag", default = false) shouldBe true
    }

    @Test
    fun `optionalBoolean rejects non-boolean values`() {
        val error = shouldThrow<McpException> {
            mapOf("flag" to "true").optionalBoolean("flag", default = false)
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "flag"
    }

    @Test
    fun `optionalPositiveInt accepts integral numbers and strings`() {
        mapOf("limit" to 5).optionalPositiveInt("limit") shouldBe 5
        mapOf("limit" to 5L).optionalPositiveInt("limit") shouldBe 5
        mapOf("limit" to "10").optionalPositiveInt("limit") shouldBe 10
    }

    @Test
    fun `optionalPositiveInt returns null only when absent`() {
        emptyMap<String, Any>().optionalPositiveInt("limit") shouldBe null
    }

    @Test
    fun `optionalPositiveInt rejects malformed and non-positive values`() {
        listOf("not-a-number", 1.9, Long.MAX_VALUE, 0, -3, true, listOf(1)).forEach { value ->
            val error = shouldThrow<McpException> {
                mapOf("limit" to value).optionalPositiveInt("limit")
            }
            error.code shouldBe McpErrorCode.INVALID_ARGUMENT
            error.message shouldContain "limit"
        }
    }

    @Test
    fun `optionalNonNegativeInt accepts zero and positive integers`() {
        mapOf("offset" to 0).optionalNonNegativeInt("offset") shouldBe 0
        mapOf("offset" to 7).optionalNonNegativeInt("offset") shouldBe 7
        emptyMap<String, Any>().optionalNonNegativeInt("offset") shouldBe null
    }

    @Test
    fun `optionalNonNegativeInt rejects malformed and negative values`() {
        listOf(-1, 2.5, "oops", false).forEach { value ->
            val error = shouldThrow<McpException> {
                mapOf("offset" to value).optionalNonNegativeInt("offset")
            }
            error.code shouldBe McpErrorCode.INVALID_ARGUMENT
            error.message shouldContain "offset"
        }
    }
}
