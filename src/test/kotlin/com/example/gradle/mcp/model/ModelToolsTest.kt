package com.example.gradle.mcp.model

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModelToolsTest {
    @Test
    fun `model query schemas expose prepareTasks property`() {
        listOf(
            projectTreeSchema(),
            modelQuerySchema(),
            invocationsQuerySchema(),
            publicationsSchema(),
            helpSchema(),
        ).forEach { schema ->
            val prepareTasks = prepareTasksProperty(schema)

            prepareTasks["type"] shouldBe "array"
            (prepareTasks["items"] as Map<*, *>)["type"] shouldBe "string"
            (prepareTasks["description"] as String) shouldContain "runs a build first and is heavier"
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun prepareTasksProperty(schema: Map<String, Any>): Map<String, Any?> =
    (schema["properties"] as Map<String, Any>)["prepareTasks"] as Map<String, Any?>
