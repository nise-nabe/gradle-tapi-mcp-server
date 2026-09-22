package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.connection.ProjectLifecycleLock
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Execution engine behind [BuildExecutionManager]: owns the shared build
 * executor, drives a [BuildRecord] through the Tooling API run pipeline, and
 * handles finalization, queue drains, and lifecycle resets.
 *
 * Locking contract: [ProjectBuildQueue] access requires the caller to hold
 * [ProjectLifecycleLock.withProjectLock] for that directory; the global lifecycle
 * lock must never be held while taking a per-project lock (see [QueueDrain]).
 */
internal class BuildRunner(
    private val connectionManager: GradleConnectionManager,
    private val registry: BuildRegistry,
    private val buildRecordStore: BuildRecordStore,
) {
    @Volatile
    private var executor: ExecutorService = newBuildExecutor()

    fun currentExecutor(): ExecutorService = executor

    /** Submit build work; throws [RejectedExecutionException] when the pool is saturated. */
    fun execute(work: () -> Unit) {
        executor.execute { work() }
    }

    fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        notifier: BuildProgressNotifier,
    ) {
        try {
            connectionManager.withConnectionResult(request.projectDirectory) { connection ->
                runBuild(record, request, connection, record.streams, record.progressTracker, notifier)
            }
        } catch (exception: Exception) {
            finalizeBuild(record, terminalOutcomeFor(exception, record))
            notifier.notifyFinal(record.progressTracker)
        } finally {
            registry.pruneCompletedBuilds()
        }
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        connection: ProjectConnection,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
        notifier: BuildProgressNotifier,
    ) {
        val effectiveRequest = when (request.kind) {
            BuildKind.TESTS -> resolveTestRunScopeAtExecution(request, record)
            else -> request
        }
        val operationLabel = when (effectiveRequest.kind) {
            BuildKind.TASKS -> "Gradle tasks: ${effectiveRequest.tasks.joinToString()}"
            BuildKind.TESTS -> describeTestOperation(effectiveRequest)
        }
        tracker.markStarting(operationLabel)
        notifier.notifyIfNeeded(tracker)

        try {
            when (effectiveRequest.kind) {
                BuildKind.TASKS -> {
                    val launcher = connection.newBuild()
                        .forTasks(*effectiveRequest.tasks.toTypedArray())
                    configureLauncher(launcher, record, effectiveRequest, streams, tracker)
                    launcher.run()
                }
                BuildKind.TESTS -> {
                    runTests(connection, record, effectiveRequest, streams, tracker)
                }
            }
            finalizeBuild(record, BuildTerminalOutcome.Succeeded)
            notifier.notifyFinal(tracker)
        } catch (exception: Exception) {
            val outcome = terminalOutcomeFor(exception, record)
            finalizeBuild(record, outcome)
            notifier.notifyFinal(tracker)
            throw exception
        }
    }

    private fun resolveTestRunScopeAtExecution(
        request: BuildRunRequest,
        record: BuildRecord,
    ): BuildRunRequest {
        if (request.testScopeValidatedAtPreflight) {
            record.selection = request.selection
            record.taskPathInferred = request.taskPathInferred
            return request
        }
        val resolved = ensureTestRunProjectScope(
            connectionManager,
            request.projectDirectory,
            TestRunOptions(selection = request.selection, tasks = request.tasks),
        )
        record.selection = resolved.options.selection
        record.taskPathInferred = resolved.taskPathInferred
        return request.copy(
            selection = resolved.options.selection,
            taskPathInferred = resolved.taskPathInferred,
        )
    }

    private fun terminalOutcomeFor(exception: Exception, record: BuildRecord? = null): BuildTerminalOutcome {
        if (exception is BuildCancelledException) {
            return BuildTerminalOutcome.Cancelled(exception.message ?: "Build cancelled")
        }
        if (isInterruptRelated(exception)) {
            record?.requestCancellationIfNeeded()
            return BuildTerminalOutcome.Cancelled(
                exception.message?.takeIf { it.isNotBlank() } ?: "Build interrupted",
            )
        }
        return BuildTerminalOutcome.Failed(BuildFailureClassifier.unwrapBuildFailureMessage(exception))
    }

    private fun isInterruptRelated(exception: Exception): Boolean =
        exception is InterruptedException || exception.cause is InterruptedException

    private fun BuildRecord.requestCancellationIfNeeded() {
        if (!cancellationTokenSource.token().isCancellationRequested) {
            cancellationTokenSource.cancel()
        }
    }

    private fun runTests(
        connection: ProjectConnection,
        record: BuildRecord,
        request: BuildRunRequest,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
    ) {
        try {
            val launcher = configureTestLauncher(connection.newTestLauncher(), request)
            configureLauncher(launcher, record, request, streams, tracker)
            launcher.run()
        } catch (exception: Exception) {
            if (!shouldFallbackToBuildLauncher(record, request, exception)) {
                throw exception
            }
            runTestsViaBuildLauncher(connection, record, request, streams, tracker)
        }
    }

    private fun shouldFallbackToBuildLauncher(
        record: BuildRecord,
        request: BuildRunRequest,
        exception: Exception,
    ): Boolean {
        val progress = record.progressTracker.snapshot()
        return TestLauncherSupport.shouldFallbackToBuildLauncher(
            request = request,
            exception = exception,
            completedTaskCount = progress.completedTaskCount,
            failedTasks = progress.failedTasks,
        )
    }

    private fun runTestsViaBuildLauncher(
        connection: ProjectConnection,
        record: BuildRecord,
        request: BuildRunRequest,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
    ) {
        val tasks = TestLauncherSupport.scopedTaskPaths(request)
        val launcher = connection.newBuild().forTasks(*tasks.toTypedArray())
        configureLauncher(launcher, record, request, streams, tracker)
        val filterArgs = TestLauncherSupport.testFilterCliArguments(request.selection)
        if (filterArgs.isNotEmpty()) {
            launcher.addArguments(*filterArgs.toTypedArray())
        }
        if (TestLauncherSupport.RERUN_ARGUMENT !in request.arguments) {
            launcher.addArguments(TestLauncherSupport.RERUN_ARGUMENT)
        }
        launcher.run()
    }

    private fun configureLauncher(
        launcher: ConfigurableLauncher<*>,
        record: BuildRecord,
        request: BuildRunRequest,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
    ) {
        GradleArgumentPolicy.validateUserBuildArguments(request.arguments, request.jvmArguments)
        val persistenceArguments = record.projectDirectory
            ?.let { buildRecordStore.launcherArguments(File(it), record.id, request.tasks) }
            .orEmpty()
        launcher.addArguments(*(request.arguments + persistenceArguments).toTypedArray())
        launcher.addJvmArguments(*request.jvmArguments.toTypedArray())
        launcher.withCancellationToken(record.cancellationTokenSource.token())
        launcher.withDetailedFailure()
        tracker.configureLauncher(launcher)
        streams.applyTo(launcher)
    }

    fun finalizeBuild(
        record: BuildRecord,
        outcome: BuildTerminalOutcome,
        queueDrain: QueueDrain = QueueDrain.ALL,
    ): Boolean {
        if (record.progressTracker.snapshot().status != BuildProgressTracker.STATUS_RUNNING) {
            return false
        }
        when (outcome) {
            BuildTerminalOutcome.Succeeded -> record.progressTracker.markSucceeded()
            is BuildTerminalOutcome.Failed -> record.progressTracker.markFailed(outcome.message)
            is BuildTerminalOutcome.Cancelled -> record.progressTracker.markCancelled(outcome.message)
        }
        val expectedStatus = when (outcome) {
            BuildTerminalOutcome.Succeeded -> BuildProgressTracker.STATUS_SUCCEEDED
            is BuildTerminalOutcome.Failed -> BuildProgressTracker.STATUS_FAILED
            is BuildTerminalOutcome.Cancelled -> BuildProgressTracker.STATUS_CANCELLED
        }
        if (record.progressTracker.snapshot().status != expectedStatus) {
            return false
        }
        if (outcome is BuildTerminalOutcome.Failed && record.errorMessage == null) {
            record.errorMessage = outcome.message
        }
        if (outcome is BuildTerminalOutcome.Cancelled && record.errorMessage == null) {
            record.errorMessage = outcome.message
        }
        record.streams.finish()
        val classified = BuildFailureClassifier.classify(
            status = record.progressTracker.snapshot().status,
            kind = record.kind.name.lowercase(),
            error = record.errorMessage,
            progress = record.progressTracker.snapshot(),
            stdout = record.streams.stdoutSnapshot().text,
        )
        record.failureKind = classified.failureKind
        if (classified.error != record.errorMessage) {
            record.errorMessage = classified.error
        }
        if (record.finishedAt == null) {
            record.finishedAt = Instant.now()
        }
        rememberCompletedBuild(record, outcome)
        buildRecordStore.writeMcpResult(record, record.progressTracker.snapshot())
        afterBuildSlotFreed(record, queueDrain)
        return true
    }

    fun finalizeQueuedBuild(record: BuildRecord, outcome: BuildTerminalOutcome.Cancelled): Boolean {
        if (record.progressTracker.snapshot().status != BuildProgressTracker.STATUS_QUEUED) {
            return false
        }
        record.progressTracker.markCancelled(outcome.message)
        record.errorMessage = outcome.message
        record.finishedAt = Instant.now()
        return true
    }

    private fun afterBuildSlotFreed(record: BuildRecord, queueDrain: QueueDrain) {
        if (queueDrain == QueueDrain.NONE) {
            return
        }
        val projectDirectory = record.projectDirectory?.let(::File) ?: return
        drainProjectQueue(projectDirectory)
        if (queueDrain == QueueDrain.ALL) {
            // Global executor may have freed a slot used by another project's requeued head.
            drainAllProjectQueues()
        }
    }

    private fun rememberCompletedBuild(record: BuildRecord, outcome: BuildTerminalOutcome) {
        val wireOutcome = when (outcome) {
            BuildTerminalOutcome.Succeeded -> "SUCCESS"
            is BuildTerminalOutcome.Failed -> "FAILED"
            is BuildTerminalOutcome.Cancelled -> "CANCELLED"
        }
        registry.storeLastCompletedBuild(record, wireOutcome)
    }

    fun markQueuedBuildsCancelled(reason: String, projectDirectory: File? = null) {
        registry.records.values
            .filter { record ->
                record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_QUEUED &&
                    record.matchesProject(projectDirectory)
            }
            .forEach { record ->
                // ProjectBuildQueue requires the per-project lifecycle lock. The
                // per-project reset path holds withProjectLock(projectDirectory), but the
                // global path (disconnect-all/shutdown) holds only global(); taking
                // the project lock here would invert the project->global lock order.
                // Cancelled entries are dropped by takeNextIfIdle's stale-head skip
                // on the next drain instead.
                if (projectDirectory != null) {
                    registry.projectQueue.remove(projectDirectory, record.id)
                }
                finalizeQueuedBuild(record, BuildTerminalOutcome.Cancelled(reason))
            }
    }

    fun markRunningBuildsCancelled(reason: String, projectDirectory: File? = null) {
        registry.records.values
            .filter { record ->
                record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING &&
                    record.matchesProject(projectDirectory)
            }
            .forEach { record ->
                record.cancellationTokenSource.cancel()
                // The per-project reset path holds withProjectLock(projectDirectory),
                // so draining that project's queue is safe. The global path
                // (disconnect-all/shutdown) holds only global(); draining would
                // take the project lock in drainProjectQueue and invert the
                // project->global lock order. Queued builds are already
                // cancelled, and disconnect callers run wakeQueuedBuilds() after
                // releasing the global lock, so the global path skips draining.
                finalizeBuild(
                    record,
                    BuildTerminalOutcome.Cancelled(reason),
                    queueDrain = if (projectDirectory == null) {
                        QueueDrain.NONE
                    } else {
                        QueueDrain.OWN_PROJECT
                    },
                )
            }
    }

    /**
     * Which project queues to drain after a running build frees its executor slot.
     */
    internal enum class QueueDrain {
        /** Drain the finished build's project queue, then every other queued project. */
        ALL,

        /** Drain only the finished build's own project queue. */
        OWN_PROJECT,

        /**
         * No queue drain. Required on the global lifecycle path
         * (disconnect-all/shutdown), which holds only global(): taking
         * the project lock in drainProjectQueue would invert the
         * project->global lock order.
         */
        NONE,
    }

    internal sealed interface BuildTerminalOutcome {
        data object Succeeded : BuildTerminalOutcome

        data class Failed(val message: String) : BuildTerminalOutcome

        data class Cancelled(val message: String) : BuildTerminalOutcome
    }

    /**
     * Whether the shared build executor should be swapped for a fresh one.
     *
     * On a per-project disconnect the project being disconnected is still in the
     * connection pool: GradleConnectionManager.disconnect runs after onDisconnect
     * so running builds can be cancelled through the live ProjectConnection.
     * Treat that project as already removed: replace the executor when every
     * still-connected project is the one being disconnected (no other connection
     * will remain). An empty pool, or a global reset with null projectDirectory,
     * also qualifies.
     */
    fun shouldReplaceExecutor(projectDirectory: File?): Boolean =
        projectDirectory == null ||
            connectionManager.connectedProjectDirectories()
                .all { ProjectDirectoryResolver.sameProject(it.path, projectDirectory) }

    fun drainAllProjectQueues() {
        registry.projectQueue.projectKeys().forEach { projectKey ->
            drainProjectQueue(File(projectKey))
        }
    }

    fun drainProjectQueue(projectDirectory: File) {
        while (true) {
            val queued = ProjectLifecycleLock.withProjectLock(projectDirectory) {
                when (
                    val result = registry.projectQueue.takeNextIfIdle(
                        projectDirectory,
                        hasRunningBuild = registry.hasRunningBuild(projectDirectory),
                    )
                ) {
                    is ProjectBuildQueue.TakeResult.Ready -> result.queued
                    ProjectBuildQueue.TakeResult.StaleRetry -> null // continue outer loop
                    ProjectBuildQueue.TakeResult.Empty,
                    ProjectBuildQueue.TakeResult.IdleOccupied,
                    -> return
                }
            }
            if (queued == null) {
                continue
            }
            try {
                executor.execute { queued.work() }
            } catch (_: RejectedExecutionException) {
                ProjectLifecycleLock.withProjectLock(projectDirectory) {
                    registry.projectQueue.requeueAtFront(projectDirectory, queued)
                }
                return
            }
        }
    }

    /** Begin executor shutdown under the caller's lifecycle lock; returns the pool to await. */
    fun beginExecutorShutdown(): ExecutorService {
        val currentExecutor = executor
        currentExecutor.shutdown()
        return currentExecutor
    }

    fun awaitExecutorTermination(executorToAwait: ExecutorService) {
        try {
            if (!executorToAwait.awaitTermination(5, TimeUnit.SECONDS)) {
                synchronized(ProjectLifecycleLock.global()) {
                    executorToAwait.shutdownNow()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun replaceBuildExecutor() {
        val oldExecutor = executor
        oldExecutor.shutdown()
        try {
            if (!oldExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                oldExecutor.shutdownNow()
                oldExecutor.awaitTermination(2, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            oldExecutor.shutdownNow()
        }
        executor = newBuildExecutor()
    }

    private fun newBuildExecutor(): ExecutorService {
        val threadCounter = AtomicInteger()
        return ThreadPoolExecutor(
            MAX_CONCURRENT_BUILDS,
            MAX_CONCURRENT_BUILDS,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable ->
                Thread(runnable, "gradle-build-runner-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    companion object {
        /**
         * Shared executor parallelism. Each running build drives a Gradle daemon
         * with its own compilation and I/O load, so the pool does not scale with
         * host core count. `GRADLE_TAPI_MAX_CONCURRENT_BUILDS` overrides the
         * default for hosts that can sustain more parallel builds.
         */
        internal val MAX_CONCURRENT_BUILDS: Int = resolveMaxConcurrentBuilds()

        private const val MIN_CONCURRENT_BUILDS = 4
        private const val DEFAULT_MAX_CONCURRENT_BUILDS = 8
        private const val MAX_CONCURRENT_BUILDS_ENV = "GRADLE_TAPI_MAX_CONCURRENT_BUILDS"

        private fun resolveMaxConcurrentBuilds(): Int {
            val override = System.getenv(MAX_CONCURRENT_BUILDS_ENV)?.trim()?.toIntOrNull()
            if (override != null && override > 0) {
                return override
            }
            return Runtime.getRuntime().availableProcessors()
                .coerceIn(MIN_CONCURRENT_BUILDS, DEFAULT_MAX_CONCURRENT_BUILDS)
        }
    }
}
