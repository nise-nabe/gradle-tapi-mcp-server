package com.example.gradle.mcp.build

import java.io.File

internal data class ActiveBuildSnapshot(
    val activeBuildId: String,
    val activeKind: String,
    val projectDirectory: String?,
    val activeStatus: String,
    val activeTasks: List<String> = emptyList(),
    val activeTestClasses: List<String> = emptyList(),
    val activeTestMethods: Map<String, List<String>> = emptyMap(),
    val activeTaskPath: String? = null,
    val activeIncludePatterns: List<String> = emptyList(),
) {
    fun toErrorFields(): Map<String, Any?> = buildMap {
        put("activeBuildId", activeBuildId)
        put("activeKind", activeKind)
        projectDirectory?.let { put("projectDirectory", it) }
        put("activeStatus", activeStatus)
        if (activeTasks.isNotEmpty()) {
            put("activeTasks", activeTasks)
        }
        if (activeTestClasses.isNotEmpty()) {
            put("activeTestClasses", activeTestClasses)
        }
        if (activeTestMethods.isNotEmpty()) {
            put("activeTestMethods", activeTestMethods)
        }
        activeTaskPath?.takeIf { it.isNotBlank() }?.let { put("activeTaskPath", it) }
        if (activeIncludePatterns.isNotEmpty()) {
            put("activeIncludePatterns", activeIncludePatterns)
        }
    }

    companion object {
        fun fromRecord(record: BuildRecord): ActiveBuildSnapshot =
            ActiveBuildSnapshot(
                activeBuildId = record.id,
                activeKind = record.kind.name.lowercase(),
                projectDirectory = record.projectDirectory,
                activeStatus = record.progressTracker.snapshot().status,
                activeTasks = record.tasks,
                activeTestClasses = record.testClasses,
                activeTestMethods = record.testMethods,
                activeTaskPath = record.taskPath,
                activeIncludePatterns = record.includePatterns,
            )

        fun forProject(
            builds: Collection<BuildRecord>,
            projectDirectory: File,
            preferredQueuedBuildId: String? = null,
        ): ActiveBuildSnapshot? {
            val active = builds.filter { record ->
                record.matchesProject(projectDirectory) &&
                    record.progressTracker.snapshot().status in ACTIVE_STATUSES
            }
            val record = active.firstOrNull {
                it.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING
            } ?: preferredQueuedBuildId?.let { buildId ->
                active.firstOrNull { it.id == buildId }
            } ?: active.firstOrNull()
            return record?.let(::fromRecord)
        }

        fun maxConcurrentBuildErrorDetails(running: Collection<BuildRecord>): Map<String, Any?> =
            buildMap {
                if (running.isNotEmpty()) {
                    put("activeBuildIds", running.map { it.id })
                    if (running.size == 1) {
                        putAll(fromRecord(running.single()).toErrorFields())
                    }
                }
            }

        private val ACTIVE_STATUSES = setOf(
            BuildProgressTracker.STATUS_RUNNING,
            BuildProgressTracker.STATUS_QUEUED,
        )
    }
}
