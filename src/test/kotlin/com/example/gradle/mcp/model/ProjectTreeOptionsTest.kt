package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProjectTreeOptionsTest {
    @Test
    fun `fromArgs parses depth and children limits`() {
        val options = ProjectTreeOptions.fromArgs(
            mapOf(
                "maxDepth" to 2,
                "maxChildren" to 5,
            ),
        )

        options.maxDepth shouldBe 2
        options.maxChildren shouldBe 5
    }

    @Test
    fun `fromArgs parses projectPath`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("projectPath" to ":plugin"))

        options.projectPath shouldBe ":plugin"
    }

    @Test
    fun `fromArgs normalizes bare projectPath`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("projectPath" to "plugin"))

        options.projectPath shouldBe ":plugin"
    }

    @ParameterizedTest
    @ValueSource(strings = ["::plugin", ":plugin:"])
    fun `fromArgs rejects malformed projectPath before model fetch`(malformedPath: String) {
        val error = shouldThrow<McpException> {
            ProjectTreeOptions.fromArgs(mapOf("projectPath" to malformedPath))
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "Invalid project path"
    }

    @Test
    fun `fromArgs parses buildTreePath`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("buildTreePath" to ":buildSrc"))

        options.buildTreePath shouldBe ":buildSrc"
        options.projectPath.shouldBeNull()
    }

    @Test
    fun `fromArgs normalizes bare buildTreePath`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("buildTreePath" to "buildSrc"))

        options.buildTreePath shouldBe ":buildSrc"
    }

    @Test
    fun `fromArgs rejects blank buildTreePath as a missing selector`() {
        val error = shouldThrow<McpException> {
            ProjectTreeOptions.fromArgs(mapOf("buildTreePath" to "  "))
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "buildTreePath is required"
    }

    @ParameterizedTest
    @ValueSource(strings = ["::plugin", ":plugin:"])
    fun `fromArgs rejects malformed buildTreePath`(malformedPath: String) {
        val error = shouldThrow<McpException> {
            ProjectTreeOptions.fromArgs(mapOf("buildTreePath" to malformedPath))
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "Invalid buildTreePath"
    }

    @Test
    fun `fromArgs rejects projectPath combined with buildTreePath`() {
        val error = shouldThrow<McpException> {
            ProjectTreeOptions.fromArgs(
                mapOf(
                    "projectPath" to ":plugin",
                    "buildTreePath" to ":buildSrc",
                ),
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "not both"
    }

    @Test
    fun `fromArgs rejects non-string buildTreePath`() {
        val error = shouldThrow<McpException> {
            ProjectTreeOptions.fromArgs(mapOf("buildTreePath" to 1))
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "must be a string"
    }

    @Test
    fun `fromArgs can skip buildTreePath parsing`() {
        val options = ProjectTreeOptions.fromArgs(
            mapOf("buildTreePath" to "  "),
            parseBuildTreePath = false,
        )

        options.buildTreePath.shouldBeNull()
    }

    @Test
    fun `fromArgs accepts root-only maxDepth and rejects invalid children limits`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("maxDepth" to 0, "maxChildren" to -1))

        options.maxDepth shouldBe 0
        options.maxChildren.shouldBeNull()
    }
}
