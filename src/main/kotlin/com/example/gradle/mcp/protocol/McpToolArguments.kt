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

@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.requiredStringList(key: String): List<String> {
    when (val value = this[key]) {
        null -> throw McpException(McpErrorCode.INVALID_ARGUMENT, "Missing required argument: $key")
        !is List<*> -> throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must be a string array: $key")
        else -> {
            if (value.any { it !is String }) {
                throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must contain only strings: $key")
            }
            val strings = value.filterIsInstance<String>()
            if (strings.isEmpty()) {
                throw McpException(McpErrorCode.INVALID_ARGUMENT, "Required argument must be a non-empty string array: $key")
            }
            return strings
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.optionalStringList(key: String): List<String>? =
    (this[key] as? List<*>)?.mapNotNull { it as? String }

fun Map<String, Any>.optionalBoolean(key: String, default: Boolean): Boolean =
    when (val value = this[key]) {
        is Boolean -> value
        else -> default
    }

fun Map<String, Any>.optionalPositiveInt(key: String): Int? =
    parseOptionalInt(key)?.takeIf { it > 0 }

fun Map<String, Any>.optionalNonNegativeInt(key: String): Int? =
    parseOptionalInt(key)?.takeIf { it >= 0 }

private fun Map<String, Any>.parseOptionalInt(key: String): Int? =
    when (val value = this[key]) {
        is Number -> value.toExactIntOrNull()
        is String -> value.toIntOrNull()
        else -> null
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
