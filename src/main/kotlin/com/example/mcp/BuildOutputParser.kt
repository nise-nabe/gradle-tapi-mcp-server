package com.example.mcp

data class BuildSummary(
    val resultLine: String?,
    val taskSummaryLine: String?,
)

object BuildOutputParser {
    private val buildResultRegex = Regex("""BUILD (SUCCESSFUL|FAILED) in .+""")
    private val taskSummaryRegex = Regex("""\d+ actionable tasks?: .+""")

    fun parse(stdout: String): BuildSummary {
        val lines = normalizeNewlines(stdout).lines()
        val resultLine = lines.asReversed().firstOrNull { buildResultRegex.containsMatchIn(it) }
        val taskSummaryLine = lines.asReversed().firstOrNull { taskSummaryRegex.containsMatchIn(it) }
        return BuildSummary(resultLine = resultLine, taskSummaryLine = taskSummaryLine)
    }

    fun toResponseMap(summary: BuildSummary): Map<String, String?> =
        mapOf(
            "resultLine" to summary.resultLine,
            "taskSummaryLine" to summary.taskSummaryLine,
        )

    fun outcomeFromStatus(status: String): String? =
        when (status) {
            BuildProgressTracker.STATUS_SUCCEEDED -> "SUCCESS"
            BuildProgressTracker.STATUS_FAILED -> "FAILED"
            else -> null
        }

    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}

