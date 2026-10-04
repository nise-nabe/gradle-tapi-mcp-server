package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.OutputNormalizer

object BuildOutputParser {
    private val buildResultRegex = Regex("""BUILD (SUCCESSFUL|FAILED) in .+""")
    private val taskSummaryRegex = Regex("""\d+ actionable tasks?: .+""")
    private val gradleFailureLineRegex = Regex("""^> (?:Task )?(.+?) FAILED\s*$""")

    /** [stderr] only supplies the result line: Gradle logs `BUILD FAILED` at error level. */
    fun parse(stdout: String, stderr: String = ""): BuildSummary {
        val lines = OutputNormalizer.normalizeNewlines(stdout).lines()
        val resultLine = lines.asReversed().firstOrNull { buildResultRegex.containsMatchIn(it) }
            ?: OutputNormalizer.normalizeNewlines(stderr).lines().asReversed()
                .firstOrNull { buildResultRegex.containsMatchIn(it) }
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

    fun summaryFromStdout(stdout: String, stderr: String = ""): Map<String, Any?>? {
        if (stdout.isBlank() && stderr.isBlank()) {
            return null
        }
        return toResponseMap(parse(stdout, stderr))
    }

    /**
     * True when Gradle's result line is `BUILD FAILED` in any of [streams] (Gradle logs it at
     * error level, so it lands on stderr); null when no stream has a result line.
     */
    fun reportsBuildFailed(vararg streams: String?): Boolean? {
        val resultLines = streams
            .filterNot { it.isNullOrBlank() }
            .mapNotNull { parse(it.orEmpty()).resultLine }
        if (resultLines.isEmpty()) {
            return null
        }
        return resultLines.any(::isBuildFailedResultLine)
    }

    fun isBuildFailedResultLine(resultLine: String): Boolean =
        resultLine.trimStart().startsWith("BUILD FAILED")

    fun outcomeFromStatus(status: String): String? =
        when (status) {
            BuildProgressTracker.STATUS_SUCCEEDED -> "SUCCESS"
            BuildProgressTracker.STATUS_FAILED -> "FAILED"
            BuildProgressTracker.STATUS_CANCELLED -> "CANCELLED"
            else -> null
        }
}
