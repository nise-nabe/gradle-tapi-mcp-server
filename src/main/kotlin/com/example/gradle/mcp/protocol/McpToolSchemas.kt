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

internal fun stringArrayProperty(description: String, minItems: Int? = null): Map<String, Any> =
    buildMap {
        put("type", "array")
        put("description", description)
        put("items", mapOf("type" to "string"))
        if (minItems != null) {
            put("minItems", minItems)
        }
    }

internal fun booleanProperty(description: String): Map<String, String> =
    mapOf("type" to "boolean", "description" to description)

internal fun integerProperty(description: String): Map<String, String> =
    mapOf("type" to "integer", "description" to description)

internal fun objectProperty(description: String): Map<String, String> =
    mapOf("type" to "object", "description" to description)

internal fun testMethodsProperty(description: String): Map<String, Any> =
    mapOf(
        "description" to description,
        "oneOf" to listOf(
            mapOf(
                "type" to "object",
                "additionalProperties" to mapOf(
                    "type" to "array",
                    "minItems" to 1,
                    "items" to mapOf("type" to "string"),
                ),
            ),
            mapOf(
                "type" to "array",
                "items" to objectSchema(
                    properties = mapOf(
                        "class" to stringProperty("Fully qualified JVM test class name"),
                        "className" to stringProperty("Alias for class"),
                        "testClass" to stringProperty("Alias for class"),
                        "methods" to stringArrayProperty("Method names in the test class", minItems = 1),
                    ),
                ),
            ),
        ),
    )
