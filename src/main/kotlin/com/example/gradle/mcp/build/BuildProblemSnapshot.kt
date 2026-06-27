package com.example.gradle.mcp.build

data class BuildProblemSnapshot(
    val label: String,
    val details: String? = null,
    val severity: String? = null,
    val solutions: List<String> = emptyList(),
    val contextualLabel: String? = null,
) {
    internal fun dedupeKey(): String =
        listOfNotNull(label, details, contextualLabel, severity).joinToString("\u0000")
}
