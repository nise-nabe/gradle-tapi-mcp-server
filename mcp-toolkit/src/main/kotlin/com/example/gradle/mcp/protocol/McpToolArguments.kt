package com.example.gradle.mcp.protocol

import kotlin.math.truncate

fun Map<String, Any>.requiredString(key: String): String {
    val value = this[key]
    if (value is String && value.isNotBlank()) {
        return value
    }
    throw McpException(
        McpErrorCode.INVALID_ARGUMENT,
        when (value) {
            null -> "Missing required argument: $key"
            is String -> "Required argument must not be blank: $key"
            else -> "Required argument must be a string: $key"
        },
    )
}

fun Map<String, Any>.optionalString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

fun Map<String, Any>.requiredStringList(key: String): List<String> {
    when (val value = this[key]) {
        null -> throw McpException(McpErrorCode.INVALID_ARGUMENT, "Missing required argument: $key")
        !is List<*> -> throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must be a string array: $key")
        else -> {
            if (value.isEmpty()) {
                throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must be a non-empty string array: $key")
            }
            return value.mapIndexed { index, item ->
                if (item !is String || item.isBlank()) {
                    throw McpException(
                        McpErrorCode.INVALID_ARGUMENT,
                        "Required argument must contain only non-blank strings: $key[$index]",
                    )
                }
                item
            }
        }
    }
}

fun Map<String, Any>.optionalStringList(key: String): List<String>? =
    when (val value = this[key]) {
        null -> null
        !is List<*> -> throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Optional argument must be a string array: $key",
        )
        else -> {
            if (value.any { it !is String }) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Optional argument must contain only strings: $key",
                )
            }
            value.filterIsInstance<String>()
        }
    }

fun Map<String, Any>.optionalBoolean(key: String, default: Boolean): Boolean =
    when (val value = this[key]) {
        null -> default
        is Boolean -> value
        // MCP clients (LLMs) sometimes encode booleans as strings; accept the
        // canonical "true"/"false" spellings instead of rejecting them.
        is String -> when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw McpException(
                McpErrorCode.INVALID_ARGUMENT,
                "Optional argument must be a boolean: $key",
            )
        }
        else -> throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Optional argument must be a boolean: $key",
        )
    }

fun Map<String, Any>.optionalPositiveInt(key: String): Int? =
    parseOptionalInt(key, "a positive integer") { it > 0 }

fun Map<String, Any>.optionalNonNegativeInt(key: String): Int? =
    parseOptionalInt(key, "a non-negative integer") { it >= 0 }

fun Map<String, Any>.optionalNonNegativeIntWithAlias(primaryKey: String, aliasKey: String): Int? =
    if (containsKey(primaryKey)) {
        optionalNonNegativeInt(primaryKey)
    } else {
        optionalNonNegativeInt(aliasKey)
    }

fun rejectUnsupportedProjectPath(args: Map<String, Any>, toolName: String) {
    val projectPath = args.optionalString("projectPath")
    if (!projectPath.isNullOrBlank()) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "projectPath is not supported on $toolName. " +
                "Use gradle_get_project_overview, gradle_get_project_model, or gradle_get_build_invocations instead.",
        )
    }
}

fun rejectUnsupportedBuildTreePath(args: Map<String, Any>, toolName: String) {
    val buildTreePath = args.optionalString("buildTreePath")
    if (!buildTreePath.isNullOrBlank()) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "buildTreePath is not supported on $toolName. " +
                "Use gradle_get_project_overview, gradle_get_project_model, " +
                "gradle_get_build_invocations, or gradle_get_project_publications instead.",
        )
    }
}

private fun Map<String, Any>.parseOptionalInt(
    key: String,
    description: String,
    inRange: (Int) -> Boolean,
): Int? {
    val parsed = when (val value = this[key]) {
        null -> return null
        is Number -> value.toExactIntOrNull()
        is String -> value.toIntOrNull()
        else -> null
    }
    if (parsed == null || !inRange(parsed)) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Optional argument must be $description: $key",
        )
    }
    return parsed
}

private fun Number.toExactIntOrNull(): Int? {
    val longValue = when (this) {
        is Int -> return this
        is Long -> this
        is Short -> toLong()
        is Byte -> toLong()
        else -> {
            val doubleValue = toDouble()
            if (!doubleValue.isFinite() || doubleValue != truncate(doubleValue)) {
                return null
            }
            doubleValue.toLong()
        }
    }
    return if (longValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        longValue.toInt()
    } else {
        null
    }
}
