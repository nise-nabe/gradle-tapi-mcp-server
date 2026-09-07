package com.example.gradle.mcp.dependency.mcp

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks long-running `gradle_index_dependency_sources` jobs so clients can poll
 * instead of holding an MCP request open (large Idea keep-sets often exceed host timeouts).
 */
class DependencySourcesIndexJobs(
    private val foregroundDetachTimeoutMs: Long = DEFAULT_FOREGROUND_DETACH_TIMEOUT_MS,
) {
    private val jobs = ConcurrentHashMap<String, IndexJob>()
    private val activeByProject = ConcurrentHashMap<String, String>()
    private val executor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "dependency-sources-index").apply { isDaemon = true }
        }

    fun start(
        projectDirectory: File,
        tokenMode: String,
        projectPath: String?,
        work: (IndexJob) -> Map<String, Any?>,
    ): IndexJob {
        val projectKey = projectDirectory.canonicalFile.absolutePath
        val indexId = "idx-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val job =
            IndexJob(
                indexId = indexId,
                projectDirectory = projectDirectory.canonicalFile,
                tokenMode = tokenMode,
                projectPath = projectPath,
            )
        val previous = activeByProject.putIfAbsent(projectKey, indexId)
        if (previous != null) {
            val active = jobs[previous]
            throw IllegalStateException(
                "Dependency-sources indexing already running for ${projectDirectory.path} " +
                    "(indexId=$previous, status=${active?.status() ?: "unknown"}). " +
                    "Poll gradle_get_dependency_sources_index_status or wait for completion.",
            )
        }
        jobs[indexId] = job
        try {
            executor.execute {
                job.markRunning()
                try {
                    val result = work(job)
                    job.markSucceeded(result)
                } catch (error: Throwable) {
                    job.markFailed(error)
                } finally {
                    activeByProject.remove(projectKey, indexId)
                    pruneFinished()
                }
            }
        } catch (error: RejectedExecutionException) {
            activeByProject.remove(projectKey, indexId)
            jobs.remove(indexId)
            throw IllegalStateException(
                "Unable to schedule dependency-sources indexing: ${error.message}",
                error,
            )
        }
        return job
    }

    fun get(indexId: String): IndexJob? = jobs[indexId]

    fun awaitOrDetach(job: IndexJob): Map<String, Any?> {
        val completed = job.await(foregroundDetachTimeoutMs)
        if (completed) {
            return job.terminalResponse()
        }
        return job.detachedResponse()
    }

    fun acceptedBackgroundResponse(job: IndexJob): Map<String, Any?> = job.acceptedResponse()

    fun statusResponse(indexId: String): Map<String, Any?> {
        val job =
            jobs[indexId]
                ?: throw IllegalArgumentException("Index job not found: $indexId")
        return job.statusResponse()
    }

    private fun pruneFinished() {
        if (jobs.size <= MAX_RETAINED_JOBS) return
        val finished =
            jobs.values
                .filter { it.isTerminal() }
                .sortedBy { it.finishedAtMs.get() ?: 0L }
        val overflow = jobs.size - MAX_RETAINED_JOBS
        finished.take(overflow).forEach { jobs.remove(it.indexId, it) }
    }

    companion object {
        const val DEFAULT_FOREGROUND_DETACH_TIMEOUT_MS: Long = 45_000L
        private const val MAX_RETAINED_JOBS: Int = 64
    }
}

class IndexJob(
    val indexId: String,
    val projectDirectory: File,
    val tokenMode: String,
    val projectPath: String?,
) {
    private val status = AtomicReference(STATUS_QUEUED)
    private val phase = AtomicReference("queued")
    private val result = AtomicReference<Map<String, Any?>?>(null)
    private val failure = AtomicReference<Throwable?>(null)
    private val completion = CountDownLatch(1)
    val startedAtMs: Long = System.currentTimeMillis()
    val finishedAtMs = AtomicReference<Long?>(null)
    private val memberCount = AtomicReference<Int?>(null)

    fun status(): String = status.get()

    fun isTerminal(): Boolean =
        status.get() == STATUS_SUCCEEDED || status.get() == STATUS_FAILED

    fun markRunning() {
        status.compareAndSet(STATUS_QUEUED, STATUS_RUNNING)
        phase.set("running")
    }

    fun markPhase(name: String, members: Int? = null) {
        phase.set(name)
        if (members != null) {
            memberCount.set(members)
        }
    }

    fun markSucceeded(response: Map<String, Any?>) {
        result.set(response)
        (response["memberCount"] as? Number)?.toInt()?.let { memberCount.set(it) }
        phase.set("done")
        status.set(STATUS_SUCCEEDED)
        finishedAtMs.set(System.currentTimeMillis())
        completion.countDown()
    }

    fun markFailed(error: Throwable) {
        failure.set(error)
        phase.set("failed")
        status.set(STATUS_FAILED)
        finishedAtMs.set(System.currentTimeMillis())
        completion.countDown()
    }

    fun await(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) {
            completion.await()
            return true
        }
        return completion.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun terminalResponse(): Map<String, Any?> {
        when (status.get()) {
            STATUS_SUCCEEDED -> {
                val body = result.get().orEmpty().toMutableMap()
                body["indexId"] = indexId
                body["status"] = STATUS_SUCCEEDED
                return body
            }
            STATUS_FAILED -> {
                val error = failure.get()
                if (error != null) {
                    throw error
                }
                throw IllegalStateException("Dependency-sources indexing failed")
            }
            else -> error("Index job $indexId is not terminal (status=${status.get()})")
        }
    }

    fun acceptedResponse(): Map<String, Any?> =
        linkedMapOf(
            "indexId" to indexId,
            "status" to status.get(),
            "background" to true,
            "projectDirectory" to projectDirectory.absolutePath,
            "tokenMode" to tokenMode,
            "projectPath" to projectPath,
            "message" to "Dependency-sources indexing started in the background.",
            "hint" to
                "Poll gradle_get_dependency_sources_index_status with indexId until status is " +
                "succeeded or failed.",
        )

    fun detachedResponse(): Map<String, Any?> =
        linkedMapOf(
            "indexId" to indexId,
            "status" to status.get(),
            "detached" to true,
            "projectDirectory" to projectDirectory.absolutePath,
            "tokenMode" to tokenMode,
            "projectPath" to projectPath,
            "phase" to phase.get(),
            "memberCount" to memberCount.get(),
            "elapsedMs" to (System.currentTimeMillis() - startedAtMs),
            "message" to
                "Dependency-sources indexing is still running; detached to avoid MCP client timeout.",
            "hint" to
                "Poll gradle_get_dependency_sources_index_status with indexId until status is " +
                "succeeded or failed.",
        )

    fun statusResponse(): Map<String, Any?> {
        val map =
            linkedMapOf<String, Any?>(
                "indexId" to indexId,
                "status" to status.get(),
                "projectDirectory" to projectDirectory.absolutePath,
                "tokenMode" to tokenMode,
                "projectPath" to projectPath,
                "phase" to phase.get(),
                "memberCount" to memberCount.get(),
                "elapsedMs" to
                    ((finishedAtMs.get() ?: System.currentTimeMillis()) - startedAtMs),
            )
        finishedAtMs.get()?.let { map["finishedAtMs"] = it }
        when (status.get()) {
            STATUS_SUCCEEDED -> {
                result.get()?.forEach { (key, value) ->
                    if (key !in map) {
                        map[key] = value
                    }
                }
            }
            STATUS_FAILED -> {
                map["error"] = failure.get()?.message ?: failure.get()?.toString()
            }
        }
        return map
    }

    companion object {
        const val STATUS_QUEUED: String = "queued"
        const val STATUS_RUNNING: String = "running"
        const val STATUS_SUCCEEDED: String = "succeeded"
        const val STATUS_FAILED: String = "failed"
    }
}
