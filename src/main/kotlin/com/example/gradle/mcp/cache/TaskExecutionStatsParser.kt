package com.example.gradle.mcp.cache

object TaskExecutionStatsParser {
    private val actionableRegex = Regex("""(\d+) actionable tasks?""")
    private val executedRegex = Regex("""(\d+) executed""")
    private val fromCacheRegex = Regex("""(\d+) from cache""")
    private val upToDateRegex = Regex("""(\d+) up-to-date""")

    fun parse(taskSummaryLine: String?): TaskExecutionStats? {
        val line = taskSummaryLine?.trim().orEmpty()
        if (line.isEmpty()) {
            return null
        }
        val actionable = actionableRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val executed = executedRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val fromCache = fromCacheRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val upToDate = upToDateRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        if (actionable == null && executed == null && fromCache == null && upToDate == null) {
            return null
        }
        return TaskExecutionStats(
            actionableTasks = actionable,
            executed = executed,
            fromCache = fromCache,
            upToDate = upToDate,
        )
    }
}
