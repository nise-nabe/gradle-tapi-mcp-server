package com.example.gradle.mcp.build

import kotlinx.serialization.Serializable

@Serializable
data class BuildProblemSnapshot(
    val label: String,
    val details: String? = null,
    val severity: String? = null,
    val solutions: List<String> = emptyList(),
    val contextualLabel: String? = null,
) {
    internal fun dedupeKey(): String =
        listOf(label, details.orEmpty(), contextualLabel.orEmpty(), severity.orEmpty())
            .joinToString("\u0000")
}
