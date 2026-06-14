package com.example.gradle.mcp.build.persistence

data class McpBuildResult(
    val schemaVersion: Int = 1,
    val buildId: String,
    val kind: String,
    val tasks: List<String>,
    val testClasses: List<String>,
    val projectDirectory: String,
    val startedAt: String,
    val finishedAt: String,
    val status: String,
    val outcome: String? = null,
    val error: String? = null,
    val buildSummary: Map<String, Any?>? = null,
    val failedTaskCount: Int = 0,
    val failedTasks: List<String> = emptyList(),
    val stdoutTotalChars: Int = 0,
    val stderrTotalChars: Int = 0,
)
