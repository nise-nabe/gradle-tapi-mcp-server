package com.example.gradle.mcp.build

internal object TaskProgressKey {
    private val taskDisplayPattern = Regex("^Task (\\S+)")

    fun fromDisplayName(displayName: String): String =
        taskDisplayPattern.find(displayName)?.groupValues?.get(1) ?: displayName
}
