package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleLock
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared build state: the record map, the per-project queue, and the
 * last-completed snapshot cache. Lock-free reads go through the concurrent
 * maps; every [ProjectBuildQueue] access must run under
 * [ProjectLifecycleLock.forProject] for that directory.
 */
internal class BuildRegistry {
    val records = ConcurrentHashMap<String, BuildRecord>()
    val projectQueue = ProjectBuildQueue()
    private val lastCompletedBuildSnapshots = ConcurrentHashMap<String, CompletedBuildSnapshot>()

    fun hasRunningBuild(projectDirectory: File? = null): Boolean =
        records.values.any { record ->
            record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING &&
                record.matchesProject(projectDirectory)
        }

    fun hasQueuedBuild(projectDirectory: File? = null): Boolean =
        records.values.any { record ->
            record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_QUEUED &&
                record.matchesProject(projectDirectory)
        }

    fun hasActiveBuild(projectDirectory: File? = null): Boolean =
        hasRunningBuild(projectDirectory) || hasQueuedBuild(projectDirectory)

    fun runningBuildId(projectDirectory: File): String? =
        records.values.firstOrNull { record ->
            record.matchesProject(projectDirectory) &&
                record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING
        }?.id

    fun runningRecords(): List<BuildRecord> =
        records.values.filter { record ->
            record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING
        }

    fun activeBuildSnapshot(projectDirectory: File): ActiveBuildSnapshot? =
        synchronized(ProjectLifecycleLock.forProject(projectDirectory)) {
            ActiveBuildSnapshot.forProject(
                builds = records.values,
                projectDirectory = projectDirectory,
                preferredQueuedBuildId = projectQueue.headBuildId(projectDirectory),
            )
        }

    fun pruneCompletedBuilds() {
        val completed = records.values
            .filter { record ->
                val status = record.progressTracker.snapshot().status
                status != BuildProgressTracker.STATUS_RUNNING &&
                    status != BuildProgressTracker.STATUS_QUEUED
            }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
        if (completed.size <= MAX_RETAINED_BUILDS) {
            return
        }
        completed.drop(MAX_RETAINED_BUILDS).forEach { records.remove(it.id) }
    }

    fun lastCompletedBuildSnapshot(projectDirectory: File): CompletedBuildSnapshot? =
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(projectDirectory)]

    fun storeLastCompletedBuild(record: BuildRecord, outcome: String) {
        val projectDirectory = record.projectDirectory ?: return
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(File(projectDirectory))] =
            CompletedBuildSnapshot(
                buildId = record.id,
                kind = record.kind,
                tasks = record.tasks,
                testClasses = record.testClasses,
                finishedAt = record.finishedAt ?: Instant.now(),
                outcome = outcome,
                stdout = record.streams.stdoutSnapshot().text,
                projectDirectory = record.projectDirectory,
            )
    }

    fun putLastCompletedSnapshot(snapshot: CompletedBuildSnapshot) {
        val projectDirectory = snapshot.projectDirectory ?: return
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(File(projectDirectory))] = snapshot
    }

    fun clearLastCompletedBuilds(projectDirectory: File?) {
        if (projectDirectory == null) {
            lastCompletedBuildSnapshots.clear()
        } else {
            lastCompletedBuildSnapshots.remove(ProjectDirectoryResolver.canonicalKey(projectDirectory))
        }
    }

    companion object {
        private const val MAX_RETAINED_BUILDS = 10
    }
}
