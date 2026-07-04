package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun Map<String, Any>.toToolSchema(): ToolSchema {
    @Suppress("UNCHECKED_CAST")
    val root = (this as Map<String, Any?>).toJsonObject()
    val required = root["required"]?.jsonArray?.map { element -> element.jsonPrimitive.content }
    return ToolSchema(
        properties = root["properties"]?.jsonObject,
        required = required,
    )
}

internal fun JsonObject?.toToolArguments(): Map<String, Any> =
    this?.toArgumentMap() ?: emptyMap()
