package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DependencyResolutionArgsTest {
    @Test
    fun `nonNegativeIntOrDefault uses zero when key is missing`() {
        emptyMap<String, Any>().nonNegativeIntOrDefault("maxDependencies") shouldBe 0
    }

    @Test
    fun `nonNegativeIntOrDefault accepts zero and positive ints`() {
        mapOf("maxDependencies" to 0).nonNegativeIntOrDefault("maxDependencies") shouldBe 0
        mapOf("maxDependencies" to 50).nonNegativeIntOrDefault("maxDependencies") shouldBe 50
    }

    @Test
    fun `nonNegativeIntOrDefault rejects overflow and negatives via exact conversion`() {
        shouldThrow<McpException> {
            mapOf("maxDependencies" to Long.MAX_VALUE).nonNegativeIntOrDefault("maxDependencies")
        }.code shouldBe McpErrorCode.INVALID_ARGUMENT

        shouldThrow<McpException> {
            mapOf("maxDependencies" to -1).nonNegativeIntOrDefault("maxDependencies")
        }.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }
}
