package com.example.gradle.mcp.protocol

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class McpJsonSupportTest {
    @Test
    fun `encodeMcpJsonDynamic round trips maps and lists`() {
        val value = mapOf(
            "count" to 2,
            "enabled" to true,
            "tags" to listOf("a", "b"),
            "nested" to mapOf("key" to "value"),
        )
        val decoded = decodeMcpJsonMap(encodeMcpJsonDynamic(value))
        decoded["count"] shouldBe 2
        decoded["enabled"] shouldBe true
        decoded["tags"] shouldBe listOf("a", "b")
        @Suppress("UNCHECKED_CAST")
        (decoded["nested"] as Map<String, Any?>)["key"] shouldBe "value"
    }

    @Test
    fun `decodeMcpJsonMap preserves integer json numbers as long`() {
        val decoded = decodeMcpJsonMap("""{"value":42}""")
        decoded["value"] shouldBe 42L
    }
}
