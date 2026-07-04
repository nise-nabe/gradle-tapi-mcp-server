package com.example.gradle.mcp.protocol

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

private val jsonMapper: JsonMapper = jsonMapper {
    addModule(kotlinModule())
}

internal fun mcpObjectMapper(): JsonMapper = jsonMapper
