package com.example.gradle.mcp.protocol

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
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
    fun `encodeMcpJsonDynamic emits valid JSON for non-finite numbers`() {
        val encoded = encodeMcpJsonDynamic(
            mapOf(
                "nan" to Double.NaN,
                "posInf" to Double.POSITIVE_INFINITY,
                "negInf" to Double.NEGATIVE_INFINITY,
                "nanFloat" to Float.NaN,
                "posInfFloat" to Float.POSITIVE_INFINITY,
                "finite" to 1.5,
            ),
        )

        // A strict JSON parser rejects bare NaN/Infinity literals, so the
        // round trip only succeeds when they were encoded as strings.
        val decoded = decodeMcpJsonMap(encoded)
        decoded["nan"] shouldBe "NaN"
        decoded["posInf"] shouldBe "Infinity"
        decoded["negInf"] shouldBe "-Infinity"
        decoded["nanFloat"] shouldBe "NaN"
        decoded["posInfFloat"] shouldBe "Infinity"
        decoded["finite"] shouldBe 1.5
    }

    @Test
    fun `decodeMcpJsonMap preserves integer json numbers as long`() {
        val decoded = decodeMcpJsonMap("""{"value":42}""")
        decoded["value"] shouldBe 42L
    }

    @Test
    fun `decodeMcpJsonMap preserves quoted numeric strings as strings`() {
        val decoded = decodeMcpJsonMap("""{"value":"42"}""")
        decoded["value"] shouldBe "42"
    }

    @Test
    fun `decodeMcpJsonMap preserves quoted boolean strings as strings`() {
        val decoded = decodeMcpJsonMap("""{"value":"true"}""")
        decoded["value"] shouldBe "true"
    }

    @Test
    fun `toArgumentMap preserves quoted numeric strings in arrays`() {
        val args = mcpJson.parseToJsonElement("""{"tasks":["compile","42"]}""").jsonObject
        args.toArgumentMap()["tasks"] shouldBe listOf("compile", "42")
    }
}
