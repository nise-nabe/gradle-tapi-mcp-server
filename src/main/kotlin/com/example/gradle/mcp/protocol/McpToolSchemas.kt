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

internal fun projectDirectoryProperty(description: String): Map<String, String> =
    stringProperty(description)

internal const val PROJECT_DIRECTORY_OPTIONAL_HINT =
    "Gradle project root. Omit for default connected project or GRADLE_PROJECT_DIR."

internal const val PROJECT_DIRECTORY_RESOLVE_HINT =
    "Gradle project root. Omit to use default connected project or GRADLE_PROJECT_DIR."

internal fun optionalProjectDirectoryProperty(): Map<String, String> =
    projectDirectoryProperty(PROJECT_DIRECTORY_OPTIONAL_HINT)

internal fun resolveRequiredProjectDirectoryProperty(): Map<String, String> =
    projectDirectoryProperty(PROJECT_DIRECTORY_RESOLVE_HINT)

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

internal fun prepareTasksProperty(): Map<String, Any> =
    stringArrayProperty("Tasks to run before model fetch (e.g. [\":app:compileJava\"]). Optional.")

internal val testMethodsClassPropertyNames = listOf("class", "className", "testClass")

internal fun testMethodsProperty(): Map<String, Any> =
    mapOf(
        "description" to
            "Map {FQCN: [methods]} or array [{class, methods}]. className and testClass aliases work at runtime.",
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
                    required = listOf("class", "methods"),
                    properties = mapOf(
                        "class" to stringProperty("Fully qualified test class"),
                        "methods" to stringArrayProperty("Test method names", minItems = 1),
                    ),
                ),
            ),
        ),
    )
