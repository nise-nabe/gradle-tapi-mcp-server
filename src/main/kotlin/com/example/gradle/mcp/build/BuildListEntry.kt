package com.example.gradle.mcp.build

import java.time.Instant

internal data class BuildListEntry(
    val buildId: String,
    val status: String,
    val kind: String?,
    val tasks: List<String>,
    val testClasses: List<String>,
    val projectDirectory: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val outcome: String?,
    val recordSource: String,
) {
    fun sortInstant(): Instant =
        parseInstant(finishedAt)
            ?: parseInstant(startedAt)
            ?: Instant.EPOCH

    fun toResponseMap(): Map<String, Any?> =
        buildMap {
            put("buildId", buildId)
            put("status", status)
            kind?.let { put("kind", it) }
            if (tasks.isNotEmpty()) {
                put("tasks", tasks)
            }
            if (testClasses.isNotEmpty()) {
                put("testClasses", testClasses)
            }
            projectDirectory?.let { put("projectDirectory", it) }
            startedAt?.let { put("startedAt", it) }
            finishedAt?.let { put("finishedAt", it) }
            outcome?.let { put("outcome", it) }
            put("recordSource", recordSource)
        }

    private fun parseInstant(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
