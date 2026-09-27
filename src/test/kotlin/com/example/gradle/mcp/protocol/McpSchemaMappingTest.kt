package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.protocol.objectSchema
import com.example.gradle.mcp.protocol.stringArrayProperty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class McpSchemaMappingTest {
    @Test
    fun `toToolArguments reads JSON object arguments`() {
        val args = buildJsonObject {
            put("projectDirectory", "/workspace")
            put("background", true)
        }.toToolArguments()

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
