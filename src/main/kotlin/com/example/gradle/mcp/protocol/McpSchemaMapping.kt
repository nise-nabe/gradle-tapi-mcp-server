package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tools.jackson.module.kotlin.readValue

private val kotlinxJson = Json { ignoreUnknownKeys = true }

internal fun Map<String, Any>.toToolSchema(): ToolSchema {
    val root = kotlinxJson.parseToJsonElement(mcpObjectMapper().writeValueAsString(this)).jsonObject
    val required = root["required"]?.jsonArray?.map { element -> element.jsonPrimitive.content }
    return ToolSchema(
        properties = root["properties"]?.jsonObject,
        required = required,
    )
}

internal fun JsonObject?.toArgumentMap(): Map<String, Any> {
    if (this == null) {
        return emptyMap()
    }
    return mcpObjectMapper().readValue(toString())
}
