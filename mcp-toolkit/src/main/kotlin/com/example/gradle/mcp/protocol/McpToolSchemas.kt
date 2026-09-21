package com.example.gradle.mcp.protocol

fun emptyObjectSchema(): Map<String, Any> =
    mapOf("type" to "object", "properties" to emptyMap<String, Any>())

fun objectSchema(
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

fun stringProperty(description: String): Map<String, String> =
    mapOf("type" to "string", "description" to description)

fun booleanProperty(description: String): Map<String, String> =
    mapOf("type" to "boolean", "description" to description)

fun integerProperty(description: String): Map<String, String> =
    mapOf("type" to "integer", "description" to description)

fun nullableIntegerProperty(description: String): Map<String, Any> =
    mapOf("type" to listOf("integer", "null"), "description" to description)

fun stringArrayProperty(
    description: String,
    minItems: Int? = null,
    itemMinLength: Int? = null,
): Map<String, Any> =
    buildMap {
        put("type", "array")
        put("description", description)
        put(
            "items",
            buildMap {
                put("type", "string")
                if (itemMinLength != null) {
                    put("minLength", itemMinLength)
                }
            },
        )
        if (minItems != null) {
            put("minItems", minItems)
        }
    }

fun objectArrayProperty(
    description: String,
    itemProperties: Map<String, Any>,
    required: List<String>,
): Map<String, Any> =
    mapOf(
        "type" to "array",
        "description" to description,
        "items" to objectSchema(properties = itemProperties, required = required),
    )
