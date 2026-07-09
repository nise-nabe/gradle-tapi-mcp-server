package com.example.gradle.mcp.build

internal object TaskProgressKey {
    private val taskDisplayPattern =
        Regex("^Task ([^ ]+) (?:started|SUCCESS|UP-TO-DATE|FAILED|skipped).*$")

    fun fromDisplayName(displayName: String): String =
        taskDisplayPattern.matchEntire(displayName)?.groupValues?.get(1) ?: displayName
}
