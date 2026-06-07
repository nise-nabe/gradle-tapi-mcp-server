package com.example.gradle.mcp.protocol

internal fun emptyObjectSchema(): Map<String, Any> =
    mapOf("type" to "object", "properties" to emptyMap<String, Any>())

internal fun objectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, Any>,
): Map<String, Any> =
    buildMap {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) {
            put("required", required)
        }
    }

internal fun stringProperty(description: String): Map<String, String> =
    mapOf("type" to "string", "description" to description)

internal fun stringArrayProperty(description: String): Map<String, Any> =
    mapOf(
        "type" to "array",
        "description" to description,
        "items" to mapOf("type" to "string"),
    )

internal fun booleanProperty(description: String): Map<String, String> =
    mapOf("type" to "boolean", "description" to description)

internal fun integerProperty(description: String): Map<String, String> =
    mapOf("type" to "integer", "description" to description)
