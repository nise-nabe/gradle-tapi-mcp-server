package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.PersistedBuildViewFactory
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectDirectoryScope
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
        scope: ProjectDirectoryScope? = null,
    ): Map<String, Any?> {
        if (!waitOptions.waitUntilComplete) {
            return statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint, scope)
        }
        val waitStartedAt = System.currentTimeMillis()
        val deadline = waitStartedAt + waitOptions.waitTimeoutMs
        var latest = statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint, scope)
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
            latest = statusOnce(buildId, outputLimit, progressOptions, projectDirectoryHint, scope)
        }
        return latest
    }

    private fun statusOnce(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        projectDirectoryHint: File? = null,
        scope: ProjectDirectoryScope? = null,
    ): Map<String, Any?> {
        val record = registry.records[buildId]
        requireMatchingProject(buildId, record, projectDirectoryHint)
        requireWithinProjectScope(buildId, record, scope)
        val projectDirectory = record?.projectDirectory?.let(::File)
            ?: projectDirectoryHint
            ?: scope?.preferredRoot()
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

    fun listBuilds(
        projectDirectoryHint: File?,
        limit: Int,
        scope: ProjectDirectoryScope? = null,
    ): Map<String, Any?> {
        val cappedLimit = limit.coerceIn(1, BuildExecutionManager.MAX_LIST_BUILDS)
        val diskProjectDirectories = diskProjectDirectories(projectDirectoryHint, scope)

        val entries = LinkedHashMap<String, BuildListEntry>()
        registry.records.values
            .asSequence()
            .filter { record -> record.matchesProject(projectDirectoryHint) }
            .filter { record -> withinScope(record, scope) }
            .forEach { record ->
                entries[record.id] = listEntryFromRecord(record, diskProjectDirectories.firstOrNull())
            }

        val diskEntries = diskProjectDirectories.flatMap { directory ->
            buildRecordStore.listBuildSortEntries(directory).map { directory to it }
        }

        val totalAvailable = entries.size + diskEntries.count { (_, entry) -> entry.buildId !in entries }

        if (diskProjectDirectories.isNotEmpty()) {
            val diskCandidates = diskEntries.filter { (_, entry) -> entry.buildId !in entries }
            val topDiskIds = buildList {
                entries.forEach { (buildId, entry) ->
                    add(buildId to entry.sortInstant().toEpochMilli())
                }
                diskCandidates.forEach { (_, candidate) ->
                    add(candidate.buildId to candidate.sortEpochMillis)
                }
            }
                .sortedByDescending { it.second }
                .take(cappedLimit)
                .map { it.first }
                .toSet()
            diskCandidates
                .filter { (_, candidate) -> candidate.buildId in topDiskIds }
                .forEach { (directory, candidate) ->
                    buildRecordStore.loadListSummary(directory, candidate.buildId)?.let { summary ->
                        entries[candidate.buildId] = summary
                    }
                }
        }

        val sorted = entries.values.sortedByDescending { it.sortInstant() }
        val limited = sorted.take(cappedLimit)
        return buildMap {
            put("builds", limited.map { it.toResponseMap() })
            (projectDirectoryHint ?: diskProjectDirectories.singleOrNull())
                ?.absolutePath?.let { put("projectDirectory", it) }
            put("totalAvailable", totalAvailable)
            put("truncated", totalAvailable > cappedLimit)
        }
    }

    private fun withinScope(record: BuildRecord, scope: ProjectDirectoryScope?): Boolean =
        scope == null ||
            record.projectDirectory == null ||
            scope.isWithinBoundary(File(record.projectDirectory))

    /**
     * Directories whose `.gradle/mcp-builds` are scanned for persisted builds:
     * the hint, else every session-scoped root, else the pool/workspace
     * default as before.
     */
    private fun diskProjectDirectories(hint: File?, scope: ProjectDirectoryScope?): List<File> =
        when {
            hint != null -> listOf(hint)
            scope != null -> scope.allowedRoots()
            else -> listOfNotNull(
                connectionManager.defaultProjectDirectory()
                    ?: ProjectDirectoryResolver.workspaceFromEnvironment(),
            )
        }

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
        return ProjectLifecycleLock.withProjectLock(projectDirectory) {
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
        return ProjectLifecycleLock.withProjectLock(projectDirectory) {
            block(projectDirectory)
        }
    }
}
