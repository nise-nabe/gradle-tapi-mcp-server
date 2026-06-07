package com.example.gradle.mcp.build

object BuildOutputParser {
    private val buildResultRegex = Regex("""BUILD (SUCCESSFUL|FAILED) in .+""")
    private val taskSummaryRegex = Regex("""\d+ actionable tasks?: .+""")
    private val gradleFailureLineRegex = Regex("""^> (?:Task )?(.+?) FAILED\s*$""")

    fun parse(stdout: String): BuildSummary {
        val lines = normalizeNewlines(stdout).lines()
        val resultLine = lines.asReversed().firstOrNull { buildResultRegex.containsMatchIn(it) }
        val taskSummaryLine = lines.asReversed().firstOrNull { taskSummaryRegex.containsMatchIn(it) }
        val failureSummary = lines.mapNotNull { line ->
            gradleFailureLineRegex.matchEntire(line.trim())?.groupValues?.get(1)
        }.distinct()
        return BuildSummary(
            resultLine = resultLine,
            taskSummaryLine = taskSummaryLine,
            failureSummary = failureSummary,
        )
    }

    fun toResponseMap(summary: BuildSummary): Map<String, Any?> =
        buildMap {
            put("resultLine", summary.resultLine)
            put("taskSummaryLine", summary.taskSummaryLine)
            if (summary.failureSummary.isNotEmpty()) {
                put("failureSummary", summary.failureSummary)
            }
        }

    fun outcomeFromStatus(status: String): String? =
        when (status) {
            BuildProgressTracker.STATUS_SUCCEEDED -> "SUCCESS"
            BuildProgressTracker.STATUS_FAILED -> "FAILED"
            else -> null
        }

    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
