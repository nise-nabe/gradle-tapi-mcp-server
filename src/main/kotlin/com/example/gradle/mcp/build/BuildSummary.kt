package com.example.gradle.mcp.build

data class BuildSummary(
    val resultLine: String?,
    val taskSummaryLine: String?,
    val failureSummary: List<String> = emptyList(),
)
