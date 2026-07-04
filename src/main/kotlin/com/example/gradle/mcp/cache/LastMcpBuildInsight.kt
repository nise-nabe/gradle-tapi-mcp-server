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
) {
    fun toResponseMap(): Map<String, Any?> = buildMap {
        put("buildId", buildId)
        put("kind", kind)
        put("tasks", tasks)
        put("testClasses", testClasses)
        put("finishedAt", finishedAt)
        put("outcome", outcome)
        put("taskSummaryLine", taskSummaryLine)
        put("resultLine", resultLine)
        put("taskStats", taskStats?.toResponseMap())
    }
}
