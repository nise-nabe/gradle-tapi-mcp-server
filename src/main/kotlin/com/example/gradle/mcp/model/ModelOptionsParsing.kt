package com.example.gradle.mcp.model

internal fun Map<String, Any>.optionalPositiveInt(key: String): Int? {
    val parsed = parseOptionalInt(key)
    return parsed?.takeIf { it > 0 }
}

internal fun Map<String, Any>.optionalNonNegativeInt(key: String): Int? {
    val parsed = parseOptionalInt(key)
    return parsed?.takeIf { it >= 0 }
}

private fun Map<String, Any>.parseOptionalInt(key: String): Int? =
    when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
