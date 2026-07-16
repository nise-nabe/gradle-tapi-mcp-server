package com.example.gradle.mcp.build

import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project FIFO queue of pending background builds.
 *
 * All mutations and reads of a project's deque must run under
 * [com.example.gradle.mcp.connection.ProjectLifecycleLock.forProject] for that directory.
 */
internal class ProjectBuildQueue {
    private val queues = ConcurrentHashMap<String, ArrayDeque<QueuedBuild>>()

    data class QueuedBuild(
        val record: BuildRecord,
        val request: BuildRunRequest,
        val work: () -> Unit,
    )

    /** Caller must hold the project lifecycle lock. */
    fun enqueue(projectDirectory: File, queued: QueuedBuild) {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        queues.computeIfAbsent(key) { ArrayDeque() }.addLast(queued)
    }

    /** Caller must hold the project lifecycle lock. */
    fun count(projectDirectory: File): Int =
        queues[ProjectDirectoryResolver.canonicalKey(projectDirectory)]?.size ?: 0

    /** Caller must hold the project lifecycle lock. */
    fun remove(projectDirectory: File, buildId: String): Boolean {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        val queue = queues[key] ?: return false
        val removed = queue.removeIf { it.record.id == buildId }
        if (queue.isEmpty()) {
            queues.remove(key)
        }
        return removed
    }

    /** Caller must hold the project lifecycle lock. */
    fun position(projectDirectory: File, buildId: String): Int? {
        val queue = queues[ProjectDirectoryResolver.canonicalKey(projectDirectory)] ?: return null
        val index = queue.indexOfFirst { it.record.id == buildId }
        return if (index >= 0) index + 1 else null
    }

    /**
     * Build waiting immediately ahead of [buildId], or [runningBuildId] when at head.
     * Caller must hold the project lifecycle lock.
     */
    fun behindBuildId(projectDirectory: File, buildId: String, runningBuildId: String?): String? {
        val queue = queues[ProjectDirectoryResolver.canonicalKey(projectDirectory)] ?: return runningBuildId
        val index = queue.indexOfFirst { it.record.id == buildId }
        if (index < 0) {
            return runningBuildId
        }
        if (index == 0) {
            return runningBuildId
        }
        return queue.elementAt(index - 1).record.id
    }

    fun projectKeys(): List<String> = queues.keys.toList()

    /**
     * Promote the head of the queue to running and remove it, or skip a stale head.
     * Caller must hold the project lifecycle lock.
     */
    fun takeNextIfIdle(projectDirectory: File, hasRunningBuild: Boolean): TakeResult {
        if (hasRunningBuild) {
            return TakeResult.IdleOccupied
        }
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        val queue = queues[key] ?: return TakeResult.Empty
        val head = queue.peekFirst() ?: run {
            queues.remove(key)
            return TakeResult.Empty
        }
        if (!head.record.progressTracker.markDequeued()) {
            // Cancelled or otherwise no longer queued — drop if still head and retry.
            if (queue.peekFirst()?.record?.id == head.record.id) {
                queue.pollFirst()
            }
            if (queue.isEmpty()) {
                queues.remove(key)
            }
            return TakeResult.StaleRetry
        }
        queue.pollFirst()
        if (queue.isEmpty()) {
            queues.remove(key)
        }
        return TakeResult.Ready(head)
    }

    /** Caller must hold the project lifecycle lock. */
    fun requeueAtFront(projectDirectory: File, queued: QueuedBuild) {
        val key = ProjectDirectoryResolver.canonicalKey(projectDirectory)
        check(queued.record.progressTracker.markQueued()) {
            "Failed to requeue build ${queued.record.id}"
        }
        queues.computeIfAbsent(key) { ArrayDeque() }.addFirst(queued)
    }

    sealed interface TakeResult {
        data class Ready(val queued: QueuedBuild) : TakeResult
        data object Empty : TakeResult
        data object IdleOccupied : TakeResult
        data object StaleRetry : TakeResult
    }
}
