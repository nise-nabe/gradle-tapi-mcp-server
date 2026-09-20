package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.PersistedBuildViewFactory
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleLock
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import java.io.File

/**
 * Read side of build tracking: merges in-memory records with on-disk artifacts
 * and shapes status / list responses. Queue fields are read under the
 * per-project lifecycle lock; record scans are lock-free.
 */
internal class BuildStatusQuery(
    private val registry: BuildRegistry,
    private val buildRecordStore: BuildRecordStore,
    private val connectionManager: GradleConnectionManager,
) {
    fun status(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        projectDirectoryHint: File? = null,
        waitOptions: BuildStatusWaitOptions = BuildStatusWaitOptions(),
    ): Map<String, Any?> {
        if (!waitOptions.waitUntilComplete) {
            return statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint)
        }
        val waitStartedAt = System.currentTimeMillis()
        val deadline = waitStartedAt + waitOptions.waitTimeoutMs
        var latest = statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint)
        while (
            latest["status"] == BuildProgressTracker.STATUS_RUNNING ||
                latest["status"] == BuildProgressTracker.STATUS_QUEUED
        ) {
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                return latest + mapOf(
                    "waitTimedOut" to true,
                    "waitedMs" to (now - waitStartedAt),
                    "hint" to BuildStatusWaitOptions.WAIT_TIMEOUT_HINT,
                )
            }
            Thread.sleep(minOf(waitOptions.pollIntervalMs, deadline - now))
            latest = statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint)
        }
        return latest
    }

    private fun statusOnce(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        projectDirectoryHint: File? = null,
    ): Map<String, Any?> {
        val record = registry.records[buildId]
        requireMatchingProject(buildId, record, projectDirectoryHint)
        val projectDirectory = record?.projectDirectory?.let(::File)
            ?: projectDirectoryHint
            ?: connectionManager.defaultProjectDirectory()
            ?: ProjectDirectoryResolver.workspaceFromEnvironment()
        val artifacts = projectDirectory?.let { buildRecordStore.loadArtifacts(it, buildId) }

        val view = when {
            record != null && artifacts != null -> {
                BuildStatusMerger.merge(
                    BuildStatusView.fromRecord(record),
                    PersistedBuildViewFactory.fromArtifacts(buildId, artifacts),
                )
            }
            record != null -> BuildStatusView.fromRecord(record)
            artifacts != null -> PersistedBuildViewFactory.fromArtifacts(buildId, artifacts)
            else -> return mapOf("status" to "not_found", "buildId" to buildId)
        }
        return withQueueFields(
            response = BuildStatusAssembler.assemble(view, outputLimit, progressOptions),
            buildId = buildId,
            projectDirectory = record?.projectDirectory?.let(::File) ?: projectDirectory,
            status = view.status,
        )
    }

    fun listBuilds(projectDirectoryHint: File?, limit: Int): Map<String, Any?> {
        val cappedLimit = limit.coerceIn(1, BuildExecutionManager.MAX_LIST_BUILDS)
        val diskProjectDirectory = resolveProjectDirectory(projectDirectoryHint)

        val entries = LinkedHashMap<String, BuildListEntry>()
        registry.records.values
            .asSequence()
            .filter { record -> record.matchesProject(projectDirectoryHint) }
            .forEach { record ->
                entries[record.id] = listEntryFromRecord(record, diskProjectDirectory)
            }

        val totalAvailable = if (diskProjectDirectory != null) {
            val diskBuildIds = buildRecordStore.listBuildIds(diskProjectDirectory)
            entries.size + diskBuildIds.count { it !in entries }
        } else {
            entries.size
        }

        if (diskProjectDirectory != null) {
            val diskCandidates = buildRecordStore.listBuildSortEntries(diskProjectDirectory)
                .filter { it.buildId !in entries }
            val topDiskIds = buildList {
                entries.forEach { (buildId, entry) ->
                    add(buildId to entry.sortInstant().toEpochMilli())
                }
                diskCandidates.forEach { candidate ->
                    add(candidate.buildId to candidate.sortEpochMillis)
                }
            }
                .sortedByDescending { it.second }
                .take(cappedLimit)
                .map { it.first }
                .toSet()
            diskCandidates
                .filter { it.buildId in topDiskIds }
                .forEach { candidate ->
                    buildRecordStore.loadListSummary(diskProjectDirectory, candidate.buildId)?.let { summary ->
                        entries[candidate.buildId] = summary
                    }
                }
        }

        val sorted = entries.values.sortedByDescending { it.sortInstant() }
        val limited = sorted.take(cappedLimit)
        return buildMap {
            put("builds", limited.map { it.toResponseMap() })
            (projectDirectoryHint ?: diskProjectDirectory)?.absolutePath?.let { put("projectDirectory", it) }
            put("totalAvailable", totalAvailable)
            put("truncated", totalAvailable > cappedLimit)
        }
    }

    private fun resolveProjectDirectory(hint: File?): File? =
        hint
            ?: connectionManager.defaultProjectDirectory()
            ?: ProjectDirectoryResolver.workspaceFromEnvironment()

    private fun listEntryFromRecord(record: BuildRecord, projectDirectory: File?): BuildListEntry {
        val snapshot = record.progressTracker.snapshot()
        var status = snapshot.status
        var outcome = BuildOutputParser.outcomeFromStatus(status)
        var recordSource = "memory"
        var statusSource: String? = null
        val memoryStatus = status
        val artifactProject = record.projectDirectory?.let(::File) ?: projectDirectory
        if (artifactProject != null &&
            status != BuildProgressTracker.STATUS_RUNNING &&
            status != BuildProgressTracker.STATUS_QUEUED
        ) {
            buildRecordStore.loadArtifacts(artifactProject, record.id)?.let { artifacts ->
                val merged = BuildStatusMerger.merge(
                    BuildStatusView.fromRecord(record),
                    PersistedBuildViewFactory.fromArtifacts(record.id, artifacts),
                )
                status = merged.status
                outcome = merged.outcome ?: BuildOutputParser.outcomeFromStatus(status)
                if (merged.status != memoryStatus) {
                    recordSource = "merged"
                    statusSource = merged.statusSource
                }
            }
        }
        val queuePosition: Int?
        val queuedBehindBuildId: String?
        if (status == BuildProgressTracker.STATUS_QUEUED) {
            val queueFields = withProjectLock(artifactProject) { dir ->
                registry.projectQueue.position(dir, record.id) to
                    registry.projectQueue.behindBuildId(dir, record.id, registry.runningBuildId(dir))
            }
            queuePosition = queueFields?.first
            queuedBehindBuildId = queueFields?.second
        } else {
            queuePosition = null
            queuedBehindBuildId = null
        }
        return BuildListEntry(
            buildId = record.id,
            status = status,
            kind = record.kind.name.lowercase(),
            tasks = record.tasks,
            selection = record.selection,
            taskPathInferred = record.taskPathInferred,
            projectDirectory = record.projectDirectory,
            startedAt = record.startedAt.toString(),
            finishedAt = record.finishedAt?.toString(),
            outcome = outcome,
            recordSource = recordSource,
            statusSource = statusSource,
            queuePosition = queuePosition,
            queuedBehindBuildId = queuedBehindBuildId,
        )
    }

    private fun withQueueFields(
        response: Map<String, Any?>,
        buildId: String,
        projectDirectory: File?,
        status: String,
    ): Map<String, Any?> {
        if (status != BuildProgressTracker.STATUS_QUEUED || projectDirectory == null) {
            return response
        }
        return synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
            response + buildMap {
                registry.projectQueue.position(projectDirectory, buildId)?.let { put("queuePosition", it) }
                registry.projectQueue.behindBuildId(projectDirectory, buildId, registry.runningBuildId(projectDirectory))
                    ?.let { put("queuedBehindBuildId", it) }
            }
        }
    }

    private fun <T> withProjectLock(projectDirectory: File?, block: (File) -> T?): T? {
        if (projectDirectory == null) {
            return null
        }
        return synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
            block(projectDirectory)
        }
    }
}
