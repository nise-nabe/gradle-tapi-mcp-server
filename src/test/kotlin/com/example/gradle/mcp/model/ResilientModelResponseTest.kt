package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResilientModelResponseTest {
    @Test
    fun `omits partial keys when there are no failures`() {
        val body = mapOf("name" to "root", "path" to ":")
        val result = ResilientModel(model = "root")

        attachResilientMetadata(body, result) shouldBe body
    }

    @Test
    fun `adds partial and failures when the model is incomplete`() {
        val body = mapOf("name" to "root", "path" to ":")
        val result = ResilientModel(
            model = "root",
            failures = listOf(
                FailureSnapshot(
                    message = "included build failed",
                    description = ":buildSrc",
                    causes = listOf("script error"),
                    problems = listOf("Compilation failed"),
                ),
            ),
            failuresTruncated = true,
        )

        attachResilientMetadata(body, result) shouldBe body + mapOf(
            "partial" to true,
            "failures" to listOf(
                mapOf(
                    "message" to "included build failed",
                    "description" to ":buildSrc",
                    "causes" to listOf("script error"),
                    "problems" to listOf("Compilation failed"),
                ),
            ),
            "failuresTruncated" to true,
        )
    }

    @Test
    fun `BUILD_FAILED details reuse the failures payload shape`() {
        val error = modelFetchFailed(
            modelName = "GradleProject",
            exception = null,
            failures = listOf(FailureSnapshot(message = "configure failed")),
            truncated = false,
        )

        error.code shouldBe McpErrorCode.BUILD_FAILED
        error.message shouldBe "Failed to fetch GradleProject: no model was produced"
        error.errorDetails shouldBe mapOf(
            "failures" to listOf(mapOf("message" to "configure failed")),
        )
    }
}
