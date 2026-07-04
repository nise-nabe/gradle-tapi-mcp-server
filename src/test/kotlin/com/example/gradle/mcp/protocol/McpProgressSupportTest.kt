package com.example.gradle.mcp.protocol

import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test

class McpProgressSupportTest {
    @Test
    fun `extractProgressToken reads token from request meta`() {
        val request = McpSchema.CallToolRequest.builder("gradle_run_tasks")
            .arguments(emptyMap())
            .meta(mapOf("progressToken" to "token-from-meta"))
            .build()

        McpProgressSupport.extractProgressToken(request) shouldBe "token-from-meta"
    }

    @Test
    fun `extractProgressToken falls back to nested arguments meta`() {
        val request = McpSchema.CallToolRequest.builder("gradle_run_tasks")
            .arguments(mapOf("_meta" to mapOf("progressToken" to 42)))
            .build()

        McpProgressSupport.extractProgressToken(request) shouldBe 42
    }

    @Test
    fun `extractProgressToken prefers request meta over nested arguments meta`() {
        val request = McpSchema.CallToolRequest.builder("gradle_run_tasks")
            .arguments(mapOf("_meta" to mapOf("progressToken" to "nested")))
            .meta(mapOf("progressToken" to "request-meta"))
            .build()

        McpProgressSupport.extractProgressToken(request) shouldBe "request-meta"
    }
}
