package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.OutputNormalizer

/**
 * Parses `gradle --dry-run` stdout into an ordered task execution plan.
 * Dry-run task lines look like `:sub:task SKIPPED`; only known task states are
 * accepted so free-form build-script println output cannot inject fake entries.
 */
object TaskExecutionPlanParser {
    private val planLineRegex = Regex("""^(:[\w.\-:]+)\s+([A-Z][A-Z\-_]*)$""")

    private val knownStates = setOf(
        "SKIPPED",
        "UP-TO-DATE",
        "FROM-CACHE",
        "NO-SOURCE",
        "EXECUTED",
        "FAILED",
        "EXCLUDED",
    )

    data class PlannedTask(val path: String, val state: String)

    fun parse(stdout: String): List<PlannedTask> =
        OutputNormalizer.normalizeNewlines(stdout)
            .lines()
            .mapNotNull { line ->
                val match = planLineRegex.matchEntire(line.trim()) ?: return@mapNotNull null
                val state = match.groupValues[2]
                if (state !in knownStates) {
                    return@mapNotNull null
                }
                PlannedTask(path = match.groupValues[1], state = state)
            }

    fun toResponseMap(tasks: List<PlannedTask>): Map<String, Any?> =
        mapOf(
            "taskCount" to tasks.size,
            "tasks" to tasks.map { task ->
                mapOf("path" to task.path, "state" to task.state)
            },
        )
}
