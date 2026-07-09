package com.example.gradle.mcp.protocol

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class McpToolSchemaSizeTest {
    @Test
    fun `all tools are registered in the catalog`() {
        val catalogNames = allMcpToolSpecs().map { it.name }
        val catalogNameSet = catalogNames.toSet()
        catalogNames.size shouldBe catalogNameSet.size
        catalogNameSet.size shouldBe 17
    }

    @Test
    fun `tool list payload stays within token budget`() {
        val tools = allMcpToolSpecs().map { spec ->
            mapOf(
                "name" to spec.name,
                "description" to spec.description,
                "inputSchema" to spec.schema,
            )
        }
        val payloadChars = encodeMcpJsonDynamic(tools).length

        payloadChars shouldBeLessThanOrEqual MAX_TOTAL_TOOLS_LIST_CHARS
        allMcpToolSpecs().forEach { spec ->
            toolDefinitionChars(spec) shouldBeLessThanOrEqual MAX_SINGLE_TOOL_CHARS
            spec.description.length shouldBeLessThanOrEqual MAX_DESCRIPTION_CHARS
        }
    }

    private companion object {
        const val MAX_TOTAL_TOOLS_LIST_CHARS = 14_000
        const val MAX_SINGLE_TOOL_CHARS = 2_800
        const val MAX_DESCRIPTION_CHARS = 220

        fun toolDefinitionChars(spec: McpToolSpec): Int =
            encodeMcpJsonDynamic(
                mapOf(
                    "name" to spec.name,
                    "description" to spec.description,
                    "inputSchema" to spec.schema,
                ),
            ).length
    }
}
