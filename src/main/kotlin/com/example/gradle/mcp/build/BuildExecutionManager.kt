package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectLifecycleLock
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpBuildNotifier
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Facade over build execution: admission (start/cancel), read models
 * ([BuildStatusQuery]), and the execution engine ([BuildRunner]) share state
 * through [BuildRegistry].
 *
 * Locking contract:
 * - Per-project mutations run under [ProjectLifecycleLock.withProjectLock].
 * - disconnect-all/shutdown run under [ProjectLifecycleLock.global] and must
 *   not take per-project locks inside (see [BuildRunner.QueueDrain]).
 */
class BuildExecutionManager(
    private val connectionManager: GradleConnectionManager,
    private val buildRecordStore: BuildRecordStore = BuildRecordStore(),
) {
    private val registry = BuildRegistry()
    private val runner = BuildRunner(connectionManager, registry, buildRecordStore)
    private val statusQuery = BuildStatusQuery(registry, buildRecordStore, connectionManager)

    fun startBackground(
        request: BuildRunRequest,
        notifier: McpBuildNotifier?,
        queueIfBusy: Boolean = false,
    ): Map<String, Any?> {
        val projectDirectory = request.projectDirectory

        ProjectLifecycleLock.withProjectLock(projectDirectory) {
            // The connection check runs under the project lock because
            // disconnect takes the same lock: a build enqueued between an
            // outside-the-lock check and enqueue would be stranded after
            // disconnect cancels the existing queue.
            connectionManager.requireConnection(projectDirectory)
            if (!registry.hasRunningBuild(projectDirectory) && !registry.hasQueuedBuild(projectDirectory)) {
                val start = newBuildStart(request, notifier, queued = false)
                return startImmediately(start, request, projectDirectory)
            }
            if (!queueIfBusy) {
                throw buildAlreadyRunningForProjectException(projectDirectory)
            }
            if (registry.projectQueue.count(projectDirectory) >= MAX_QUEUED_PER_PROJECT) {
                throw buildQueueFullException(projectDirectory)
            }
            val start = newBuildStart(request, notifier, queued = true)
            return enqueueBackground(start, request, projectDirectory)
        }
    }

    suspend fun runForeground(
        request: BuildRunRequest,
        notifier: McpBuildNotifier?,
        foregroundDetachTimeoutMs: Long = DEFAULT_FOREGROUND_DETACH_TIMEOUT_MS,
    ): Map<String, Any?> {
        val start = newBuildStart(request, notifier)
        val buildId = registerBuildStart(start)
        val completion = CountDownLatch(1)

        submitBuild(buildId, request.projectDirectory) {
            try {
                runner.runBuild(start.record, request, start.notifier)
            } finally {
                completion.countDown()
            }
        }

        return withContext(NonCancellable) {
            try {
                val completedInTime = awaitBuildCompletion(completion, foregroundDetachTimeoutMs)
                if (completedInTime) {
                    BuildStatusAssembler.assemble(
                        view = BuildStatusView.fromRecord(start.record),
                        outputLimit = request.outputLimit,
                        progressOptions = request.progressOptions,
                        style = BuildStatusResponseStyle.FOREGROUND,
                    )
                } else {
                    detachedForegroundResponse(start.record, request)
                }
            } catch (_: InterruptedException) {
                Thread.interrupted()
                detachedForegroundResponse(start.record, request)
            } finally {
                registry.pruneCompletedBuilds()
            }
        }
    }

    private fun awaitBuildCompletion(completion: CountDownLatch, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) {
            return completion.await(0L, TimeUnit.MILLISECONDS)
        }
        return completion.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun cancelBuild(buildId: String, projectDirectoryHint: File? = null): Map<String, Any?> {
        val record = registry.records[buildId]
            ?: throw McpException(McpErrorCode.INVALID_ARGUMENT, "Build not found: $buildId")
        requireMatchingProject(buildId, record, projectDirectoryHint)
        val projectDirectory = record.projectDirectory?.let(::File)
        if (projectDirectory != null) {
            ProjectLifecycleLock.withProjectLock(projectDirectory) {
                return cancelBuildUnderLock(buildId, record, projectDirectory)
            }
        }
        return cancelBuildWithoutProjectLock(buildId, record)
    }

    private fun cancelBuildUnderLock(
        buildId: String,
        record: BuildRecord,
        projectDirectory: File,
    ): Map<String, Any?> {
        val status = record.progressTracker.snapshot().status
        return when (status) {
            BuildProgressTracker.STATUS_QUEUED -> {
                registry.projectQueue.remove(projectDirectory, buildId)
                if (!runner.finalizeQueuedBuild(record, BuildRunner.BuildTerminalOutcome.Cancelled("Build cancelled"))) {
                    record.cancellationTokenSource.cancel()
                    return cancellationRequestedResponse(buildId)
                }
                val response = queuedCancelledResponse(buildId, record)
                runner.drainProjectQueue(projectDirectory)
                response
            }
            BuildProgressTracker.STATUS_RUNNING -> {
                record.cancellationTokenSource.cancel()
                cancellationRequestedResponse(buildId)
            }
            else -> alreadyFinishedCancelResponse(buildId, record, status)
        }
    }

    private fun cancelBuildWithoutProjectLock(buildId: String, record: BuildRecord): Map<String, Any?> {
        val status = record.progressTracker.snapshot().status
        return when (status) {
            BuildProgressTracker.STATUS_QUEUED ->
                throw McpException(McpErrorCode.INTERNAL_ERROR, "Build $buildId missing projectDirectory")
            BuildProgressTracker.STATUS_RUNNING -> {
                record.cancellationTokenSource.cancel()
                cancellationRequestedResponse(buildId)
            }
            else -> alreadyFinishedCancelResponse(buildId, record, status)
        }
    }

    fun status(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        projectDirectoryHint: File? = null,
        waitOptions: BuildStatusWaitOptions = BuildStatusWaitOptions(),
    ): Map<String, Any?> =
        statusQuery.status(buildId, outputLimit, progressOptions, projectDirectoryHint, waitOptions)

    fun listBuilds(projectDirectoryHint: File?, limit: Int): Map<String, Any?> =
        statusQuery.listBuilds(projectDirectoryHint, limit)

    internal fun activeBuildSnapshot(projectDirectory: File): ActiveBuildSnapshot? =
        registry.activeBuildSnapshot(projectDirectory)

    fun hasActiveBuild(projectDirectory: File? = null): Boolean =
        registry.hasActiveBuild(projectDirectory)

    fun hasRunningBuild(projectDirectory: File? = null): Boolean =
        registry.hasRunningBuild(projectDirectory)

    fun hasQueuedBuild(projectDirectory: File? = null): Boolean =
        registry.hasQueuedBuild(projectDirectory)

    fun resetBuildState(reason: String, projectDirectory: File? = null) {
        ProjectLifecycleLock.withLifecycleLock(projectDirectory) {
            runner.markQueuedBuildsCancelled(reason, projectDirectory)
            runner.markRunningBuildsCancelled(reason, projectDirectory)
            if (runner.shouldReplaceExecutor(projectDirectory)) {
                runner.replaceBuildExecutor()
            }
        }
    }

    /**
     * Promote queued builds after lifecycle reset or disconnect.
     * Call only after releasing any per-project lifecycle lock held by the caller.
     */
    fun wakeQueuedBuilds(projectDirectory: File? = null) {
        if (projectDirectory != null) {
            runner.drainProjectQueue(projectDirectory)
        }
        runner.drainAllProjectQueues()
    }

    fun onDisconnect(projectDirectory: File? = null) {
        resetBuildState("Gradle connection closed", projectDirectory)
        registry.clearLastCompletedBuilds(projectDirectory)
    }

    fun shutdown() {
        val executorToAwait = synchronized(ProjectLifecycleLock.global()) {
            runner.markQueuedBuildsCancelled("Server shutting down")
            runner.markRunningBuildsCancelled("Server shutting down")
            runner.beginExecutorShutdown()
        }
        runner.awaitExecutorTermination(executorToAwait)
    }

    internal fun lastCompletedBuildSnapshot(projectDirectory: File): CompletedBuildSnapshot? =
        registry.lastCompletedBuildSnapshot(projectDirectory)

    private fun newBuildStart(
        request: BuildRunRequest,
        notifier: McpBuildNotifier?,
        queued: Boolean = false,
    ): BuildStart {
        val streams = CapturingStreams()
        val progressNotifier = BuildProgressNotifier(notifier)
        lateinit var tracker: BuildProgressTracker
        tracker = BuildProgressTracker(
            trackDownloads = request.progressOptions.includeDownloads,
            onUpdate = { progressNotifier.notifyIfNeeded(tracker) },
            initialStatus = if (queued) {
                BuildProgressTracker.STATUS_QUEUED
            } else {
                BuildProgressTracker.STATUS_RUNNING
            },
        )
        val record = BuildRecord(
            id = UUID.randomUUID().toString(),
            kind = request.kind,
            tasks = request.tasks,
            selection = request.selection,
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
            projectDirectory = request.projectDirectory.absolutePath,
        ).also { seeded ->
            seeded.taskPathInferred = request.taskPathInferred
        }
        return BuildStart(record, progressNotifier)
    }

    private data class BuildStart(
        val record: BuildRecord,
        val notifier: BuildProgressNotifier,
    )

    private fun startImmediately(
        start: BuildStart,
        request: BuildRunRequest,
        projectDirectory: File,
    ): Map<String, Any?> {
        val buildId = registerImmediateBuildStart(start, projectDirectory)
        submitBuild(buildId, projectDirectory) {
            runner.runBuild(start.record, request, start.notifier)
        }
        return runningBackgroundResponse(start.record.id, request)
    }

    private fun enqueueBackground(
        start: BuildStart,
        request: BuildRunRequest,
        projectDirectory: File,
    ): Map<String, Any?> {
        check(start.record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_QUEUED) {
            "Queued build ${start.record.id} must start in queued status"
        }
        registry.records[start.record.id] = start.record
        registry.projectQueue.enqueue(
            projectDirectory,
            ProjectBuildQueue.QueuedBuild(
                record = start.record,
                request = request,
                work = { runner.runBuild(start.record, request, start.notifier) },
            ),
        )
        registry.pruneCompletedBuilds()
        // Caller holds the project lifecycle lock, so queue field reads are safe here.
        return queuedBackgroundResponse(
            buildId = start.record.id,
            request = request,
            queuePosition = registry.projectQueue.position(projectDirectory, start.record.id),
            queuedBehindBuildId = registry.projectQueue.behindBuildId(
                projectDirectory,
                start.record.id,
                registry.runningBuildId(projectDirectory),
            ),
        )
    }

    /**
     * Shared submit path for foreground and immediate background starts.
     * A saturated global pool drops the just-registered record and reports the
     * same BUILD_ALREADY_RUNNING error from both call sites.
     */
    private fun submitBuild(buildId: String, projectDirectory: File, work: () -> Unit) {
        try {
            runner.execute(work)
        } catch (_: RejectedExecutionException) {
            ProjectLifecycleLock.withProjectLock(projectDirectory) {
                registry.records.remove(buildId)
            }
            throw maxConcurrentBuildsException()
        }
    }

    private fun registerImmediateBuildStart(start: BuildStart, projectDirectory: File): String {
        ProjectLifecycleLock.withProjectLock(projectDirectory) {
            // Under the project lock for the same reason as startBackground:
            // a disconnect must not interleave between this check and record
            // registration.
            connectionManager.requireConnection(projectDirectory)
            if (registry.hasActiveBuild(projectDirectory)) {
                throw buildAlreadyRunningForProjectException(projectDirectory)
            }
            registry.records[start.record.id] = start.record
            registry.pruneCompletedBuilds()
            return start.record.id
        }
    }

    private fun registerBuildStart(start: BuildStart): String {
        val projectDirectory = start.record.projectDirectory?.let { File(it) }
            ?: error("Build record missing projectDirectory")
        return registerImmediateBuildStart(start, projectDirectory)
    }


    private fun buildAlreadyRunningForProjectException(projectDirectory: File): McpException =
        McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "A Gradle build is already active for ${projectDirectory.path}. " +
                "Poll gradle_get_build_status with the active buildId, call gradle_cancel_build to stop it, " +
                "wait for it to finish, or retry with background=true to enqueue.",
            errorDetails = registry.activeBuildSnapshot(projectDirectory)?.toErrorFields().orEmpty(),
        )

    private fun buildQueueFullException(projectDirectory: File): McpException =
        McpException(
            McpErrorCode.BUILD_QUEUE_FULL,
            "Build queue is full for ${projectDirectory.path} " +
                "(max $MAX_QUEUED_PER_PROJECT queued builds). " +
                "Poll or cancel queued builds with gradle_get_build_status / gradle_cancel_build.",
            errorDetails = registry.activeBuildSnapshot(projectDirectory)?.toErrorFields().orEmpty(),
        )

    private fun maxConcurrentBuildsException(): McpException {
        val errorDetails = synchronized(ProjectLifecycleLock.global()) {
            ActiveBuildSnapshot.maxConcurrentBuildErrorDetails(registry.runningRecords())
        }
        return McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "Maximum concurrent builds (${BuildRunner.MAX_CONCURRENT_BUILDS}) reached. " +
                "Poll gradle_get_build_status with activeBuildIds, or wait for a build to finish.",
            errorDetails = errorDetails,
        )
    }

    internal fun seedRunningBuildForTests(record: BuildRecord) {
        registry.records[record.id] = record
    }

    internal fun seedQueuedBuildForTests(record: BuildRecord, request: BuildRunRequest) {
        registry.records[record.id] = record
        val projectDirectory = record.projectDirectory?.let(::File) ?: return
        ProjectLifecycleLock.withProjectLock(projectDirectory) {
            registry.projectQueue.enqueue(
                projectDirectory,
                ProjectBuildQueue.QueuedBuild(
                    record = record,
                    request = request,
                    work = { runner.runBuild(record, request, BuildProgressNotifier(null)) },
                ),
            )
        }
    }

    internal fun queueDepthForTests(projectDirectory: File): Int =
        ProjectLifecycleLock.withProjectLock(projectDirectory) {
            registry.projectQueue.count(projectDirectory)
        }

    internal fun completeBuildForTests(buildId: String, succeeded: Boolean = true): Boolean {
        val record = registry.records[buildId] ?: return false
        val outcome = if (succeeded) {
            BuildRunner.BuildTerminalOutcome.Succeeded
        } else {
            BuildRunner.BuildTerminalOutcome.Failed("Build failed")
        }
        return runner.finalizeBuild(record, outcome)
    }

    internal fun seedLastCompletedBuildForTests(snapshot: CompletedBuildSnapshot) {
        registry.putLastCompletedSnapshot(snapshot)
    }

    internal fun executorForTests(): ExecutorService = runner.currentExecutor()

    internal fun maxConcurrentBackgroundBuilds(): Int = BuildRunner.MAX_CONCURRENT_BUILDS

    companion object {
        private const val MAX_QUEUED_PER_PROJECT = 3
        internal const val DEFAULT_FOREGROUND_DETACH_TIMEOUT_MS = 45_000L
        internal const val DEFAULT_LIST_BUILDS = 20
        internal const val MAX_LIST_BUILDS = 100
    }
}
