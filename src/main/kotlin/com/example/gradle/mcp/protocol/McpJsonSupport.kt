package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

private val jsonMapper: JsonMapper = jsonMapper {
    addModule(kotlinModule())
}

internal val mcpJsonMapper: McpJsonMapper = JacksonMcpJsonMapper(jsonMapper)

internal fun mcpObjectMapper(): JsonMapper = jsonMapper
