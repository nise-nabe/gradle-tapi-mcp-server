package com.example.gradle.mcp.build

data class ProgressEventSnapshot(
    val timestamp: String,
    val eventType: String,
    val displayName: String,
    val outcome: String? = null,
)

data class BuildProgressSnapshot(
    val status: String,
    val currentOperation: String?,
    val completedTaskCount: Int,
    val runningTaskCount: Int,
    val failedTaskCount: Int,
    val completedTasks: List<String>,
    val runningTasks: List<String>,
    val failedTasks: List<String>,
    val recentEvents: List<ProgressEventSnapshot>,
    val totalEventCount: Int,
)
