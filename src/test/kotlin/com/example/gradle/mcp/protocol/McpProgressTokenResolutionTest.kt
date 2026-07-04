package com.example.gradle.mcp.protocol

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class McpProgressTokenResolutionTest {
    @Test
    fun `resolveProgressToken prefers request meta`() {
        val token = resolveProgressToken(ProgressToken("from-meta"), mapOf("_meta" to mapOf("progressToken" to "from-args")))
        token shouldBe ProgressToken("from-meta")
    }

    @Test
    fun `resolveProgressToken falls back to arguments meta`() {
        val token = resolveProgressToken(null, mapOf("_meta" to mapOf("progressToken" to "from-args")))
        token shouldBe ProgressToken("from-args")
    }

    @Test
    fun `resolveProgressToken accepts numeric argument token`() {
        val token = resolveProgressToken(null, mapOf("_meta" to mapOf("progressToken" to 42)))
        token shouldBe ProgressToken(42)
    }

    @Test
    fun `resolveProgressToken preserves quoted numeric string from decoded arguments`() {
        val args = mcpJson.parseToJsonElement(
            """{"_meta":{"progressToken":"42"}}""",
        ).jsonObject.toArgumentMap()
        val token = resolveProgressToken(null, args)
        token shouldBe ProgressToken("42")
    }

    @Test
    fun `resolveProgressToken returns null when absent`() {
        resolveProgressToken(null, emptyMap()).shouldBeNull()
    }
}
