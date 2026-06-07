package com.example.gradle.mcp.cache

data class TaskExecutionStats(
    val actionableTasks: Int?,
    val executed: Int?,
    val fromCache: Int?,
    val upToDate: Int?,
)
