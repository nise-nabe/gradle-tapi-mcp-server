package com.example.gradle.mcp.protocol

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

internal fun prepareTasksProperty(): Map<String, Any> =
    stringArrayProperty("Prefetch tasks (e.g. [\":app:compileJava\"])")

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
