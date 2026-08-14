package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class BuildToolsQueueIfBusyTest {
    @Test
    fun `omitted queueIfBusy is false without background`() {
        requireQueueIfBusyWithBackground(emptyMap()) shouldBe false
        requireQueueIfBusyWithBackground(mapOf("background" to false)) shouldBe false
    }

    @Test
    fun `omitted queueIfBusy is true when background is true`() {
        requireQueueIfBusyWithBackground(mapOf("background" to true)) shouldBe true
    }

    @Test
    fun `explicit queueIfBusy false is preserved with background`() {
        requireQueueIfBusyWithBackground(
            mapOf("background" to true, "queueIfBusy" to false),
        ) shouldBe false
    }

    @Test
    fun `explicit queueIfBusy true is preserved with background`() {
        requireQueueIfBusyWithBackground(
            mapOf("background" to true, "queueIfBusy" to true),
        ) shouldBe true
    }

    @Test
    fun `queueIfBusy true without background is invalid`() {
        val error = shouldThrow<McpException> {
            requireQueueIfBusyWithBackground(mapOf("queueIfBusy" to true))
        }
        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "queueIfBusy requires background=true"
    }

    @Test
    fun `run schemas document queueIfBusy default with background`() {
        val expected = "Enqueue if busy (true if background)."
        queueIfBusyDescription(runTasksSchema()) shouldBe expected
        queueIfBusyDescription(runTestsSchema()) shouldBe expected
    }

    @Suppress("UNCHECKED_CAST")
    private fun queueIfBusyDescription(schema: Map<String, Any>): String {
        val properties = schema.getValue("properties") as Map<String, Map<String, Any>>
        return properties.getValue("queueIfBusy").getValue("description") as String
    }
}
