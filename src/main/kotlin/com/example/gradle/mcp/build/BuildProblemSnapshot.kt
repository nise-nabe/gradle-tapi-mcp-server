package com.example.gradle.mcp.build

import kotlinx.serialization.Serializable

@Serializable
data class ProblemLocationSnapshot(
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

@Serializable
data class BuildProblemSnapshot(
    val label: String,
    val details: String? = null,
    val severity: String? = null,
    val solutions: List<String> = emptyList(),
    val contextualLabel: String? = null,
    val originLocations: List<ProblemLocationSnapshot> = emptyList(),
    val contextualLocations: List<ProblemLocationSnapshot> = emptyList(),
) {
    internal fun dedupeKey(): String =
        listOf(
            label,
            details.orEmpty(),
            contextualLabel.orEmpty(),
            severity.orEmpty(),
            locationDedupe(originLocations),
            locationDedupe(contextualLocations),
        ).joinToString("\u0000")

    private fun locationDedupe(locations: List<ProblemLocationSnapshot>): String =
        locations.joinToString("\u001f") { "${it.path.orEmpty()}:${it.line ?: ""}:${it.column ?: ""}" }
}
