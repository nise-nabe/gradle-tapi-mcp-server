package com.example.gradle.mcp.cache

data class LastMcpBuildInsight(
    val buildId: String,
    val kind: String,
    val tasks: List<String>,
    val testClasses: List<String>,
    val finishedAt: String,
    val outcome: String,
    val taskSummaryLine: String?,
    val resultLine: String?,
    val taskStats: TaskExecutionStats?,
)
