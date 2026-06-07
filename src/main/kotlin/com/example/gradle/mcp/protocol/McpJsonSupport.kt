package com.example.gradle.mcp.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper

private val objectMapper: ObjectMapper = jacksonObjectMapper()

internal val mcpJsonMapper: McpJsonMapper = JacksonMcpJsonMapper(objectMapper)

internal fun mcpObjectMapper(): ObjectMapper = objectMapper
