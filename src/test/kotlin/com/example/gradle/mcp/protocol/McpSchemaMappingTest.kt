package com.example.gradle.mcp.protocol

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class McpSchemaMappingTest {
    @Test
    fun `toArgumentMap reads JSON object arguments`() {
        val args = buildJsonObject {
            put("projectDirectory", "/workspace")
            put("background", true)
        }.toArgumentMap()

        args["projectDirectory"] shouldBe "/workspace"
        args["background"] shouldBe true
    }

    @Test
    fun `toToolSchema preserves required properties`() {
        val schema = objectSchema(
            required = listOf("tasks"),
            properties = mapOf(
                "tasks" to stringArrayProperty("Gradle task paths"),
            ),
        ).toToolSchema()

        schema.required shouldBe listOf("tasks")
        schema.properties?.containsKey("tasks") shouldBe true
    }
}

class RequestMetaProgressTokenTest {
    @Test
    fun `RequestMeta exposes string progress token`() {
        val meta = RequestMeta(buildJsonObject { put("progressToken", "token-from-meta") })
        meta.progressToken.shouldNotBeNull()
    }

    @Test
    fun `RequestMeta exposes numeric progress token`() {
        val meta = RequestMeta(buildJsonObject { put("progressToken", 42) })
        meta.progressToken.shouldNotBeNull()
    }
}
