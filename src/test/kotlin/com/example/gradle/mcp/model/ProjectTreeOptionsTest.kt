package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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

    @Test
    fun `fromArgs rejects malformed projectPath before model fetch`() {
        val error = shouldThrow<McpException> {
            ProjectTreeOptions.fromArgs(mapOf("projectPath" to "::plugin"))
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "Invalid project path"
    }

    @Test
    fun `fromArgs accepts root-only maxDepth and rejects invalid children limits`() {
        val options = ProjectTreeOptions.fromArgs(mapOf("maxDepth" to 0, "maxChildren" to -1))

        options.maxDepth shouldBe 0
        options.maxChildren.shouldBeNull()
    }
}
