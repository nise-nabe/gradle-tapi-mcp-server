package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.model.OutputLimiter
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpProgressSupport
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.optionalProgressFields
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.gradle.tooling.ProjectConnection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BuildExecutionManager(
    private val connectionManager: GradleConnectionManager,
) {
    private val lifecycleLock = Any()
    private var executor: ExecutorService = newBuildExecutor()
    private val builds = ConcurrentHashMap<String, BuildRecord>()
    @Volatile
    private var lastCompletedBuildSnapshot: CompletedBuildSnapshot? = null

    fun startBackground(
        request: BuildRunRequest,
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
    ): Map<String, Any?> {
        connectionManager.withConnection { }

        val start = newBuildStart(request, exchange, progressToken)
        val buildId = start.record.id

        synchronized(lifecycleLock) {
            builds[buildId] = start.record
            pruneCompletedBuilds()
            try {
                executor.execute {
                    runBuild(start.record, request, start.notifier)
                }
            } catch (_: RejectedExecutionException) {
                builds.remove(buildId)
                throw maxConcurrentBuildsException()
            }
        }

        return mapOf(
            "buildId" to buildId,
            "status" to BuildProgressTracker.STATUS_RUNNING,
            "kind" to request.kind.name.lowercase(),
            "tasks" to request.tasks,
            "testClasses" to request.testClasses,
            "message" to "Build started in background. Poll gradle_get_build_status with this buildId.",
        )
    }

    fun runForeground(
        request: BuildRunRequest,
        connection: ProjectConnection,
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
    ): Map<String, Any?> {
        val start = newBuildStart(request, exchange, progressToken)
        builds[start.record.id] = start.record
        return try {
            runBuild(
                start.record,
                request,
                connection,
                start.record.streams,
                start.record.progressTracker,
                start.notifier,
            )
            foregroundSuccessResponse(start.record, request, start.record.progressTracker)
        } catch (_: Exception) {
            foregroundFailureResponse(start.record, request, start.record.progressTracker)
        } finally {
            pruneCompletedBuilds()
        }
    }

    fun status(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
    ): Map<String, Any?> {
        val record = builds[buildId]
            ?: return mapOf("status" to "not_found", "buildId" to buildId)

        return buildStatusResponse(record, outputLimit, progressOptions)
    }

    fun hasActiveBuild(): Boolean =
        builds.values.any { it.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING }

    fun resetBuildState(reason: String) {
        synchronized(lifecycleLock) {
            markRunningBuildsFailed(reason)
            replaceBuildExecutor()
        }
    }

    fun onDisconnect() {
        resetBuildState("Gradle connection closed")
    }

    fun shutdown() {
        val executorToAwait = synchronized(lifecycleLock) {
            markRunningBuildsFailed("Server shutting down")
            val currentExecutor = executor
            currentExecutor.shutdownNow()
            currentExecutor
        }
        try {
            executorToAwait.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun markRunningBuildsFailed(reason: String) {
        builds.values
            .filter { it.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING }
            .forEach { record -> finalizeBuild(record, BuildTerminalOutcome.Failed(reason)) }
    }

    private sealed interface BuildTerminalOutcome {
        data object Succeeded : BuildTerminalOutcome

        data class Failed(val message: String) : BuildTerminalOutcome
    }

    private fun finalizeBuild(record: BuildRecord, outcome: BuildTerminalOutcome): Boolean {
        if (record.progressTracker.snapshot().status != BuildProgressTracker.STATUS_RUNNING) {
            return false
        }
        when (outcome) {
            BuildTerminalOutcome.Succeeded -> record.progressTracker.markSucceeded()
            is BuildTerminalOutcome.Failed -> record.progressTracker.markFailed(outcome.message)
        }
        val expectedStatus = when (outcome) {
            BuildTerminalOutcome.Succeeded -> BuildProgressTracker.STATUS_SUCCEEDED
            is BuildTerminalOutcome.Failed -> BuildProgressTracker.STATUS_FAILED
        }
        if (record.progressTracker.snapshot().status != expectedStatus) {
            return false
        }
        if (outcome is BuildTerminalOutcome.Failed && record.errorMessage == null) {
            record.errorMessage = outcome.message
        }
        if (record.finishedAt == null) {
            record.finishedAt = Instant.now()
        }
        rememberCompletedBuild(record, outcome)
        return true
    }

    internal fun lastCompletedBuildSnapshot(): CompletedBuildSnapshot? = lastCompletedBuildSnapshot

    private fun rememberCompletedBuild(record: BuildRecord, outcome: BuildTerminalOutcome) {
        val buildOutcome = when (outcome) {
            BuildTerminalOutcome.Succeeded -> "SUCCESS"
            is BuildTerminalOutcome.Failed -> "FAILED"
        }
        lastCompletedBuildSnapshot = CompletedBuildSnapshot(
            buildId = record.id,
            kind = record.kind,
            tasks = record.tasks,
            testClasses = record.testClasses,
            finishedAt = record.finishedAt ?: Instant.now(),
            outcome = buildOutcome,
            stdout = record.streams.stdoutSnapshot().text,
            projectDirectory = record.projectDirectory,
        )
    }

    private fun newBuildStart(
        request: BuildRunRequest,
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
    ): BuildStart {
        val streams = CapturingStreams()
        val notifier = ProgressNotifier(exchange, progressToken)
        lateinit var tracker: BuildProgressTracker
        tracker = BuildProgressTracker(onUpdate = { notifier.notifyIfNeeded(tracker) })
        val record = BuildRecord(
            id = UUID.randomUUID().toString(),
            kind = request.kind,
            tasks = request.tasks,
            testClasses = request.testClasses,
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
            projectDirectory = connectionManager.connectedProjectDirectory()?.absolutePath,
        )
        return BuildStart(record, notifier)
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        notifier: ProgressNotifier,
    ) {
        try {
            connectionManager.withConnection { connection ->
                runBuild(record, request, connection, record.streams, record.progressTracker, notifier)
            }
        } catch (exception: Exception) {
            finalizeBuild(record, BuildTerminalOutcome.Failed(exception.message ?: exception.toString()))
            notifier.notifyFinal(record.progressTracker)
        } finally {
            pruneCompletedBuilds()
        }
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        connection: ProjectConnection,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
        notifier: ProgressNotifier,
    ) {
        val operationLabel = when (request.kind) {
            BuildKind.TASKS -> "Gradle tasks: ${request.tasks.joinToString()}"
            BuildKind.TESTS -> "Gradle tests: ${request.testClasses.joinToString()}"
        }
        tracker.markStarting(operationLabel)
        notifier.notifyIfNeeded(tracker)

        try {
            when (request.kind) {
                BuildKind.TASKS -> {
                    val launcher = connection.newBuild()
                        .forTasks(*request.tasks.toTypedArray())
                        .addArguments(request.arguments)
                        .addJvmArguments(request.jvmArguments)
                    tracker.configureLauncher(launcher)
                    streams.applyTo(launcher)
                    launcher.run()
                }
                BuildKind.TESTS -> {
                    val launcher = connection.newTestLauncher()
                        .withJvmTestClasses(*request.testClasses.toTypedArray())
                        .addArguments(request.arguments)
                        .addJvmArguments(request.jvmArguments)
                    tracker.configureLauncher(launcher)
                    streams.applyTo(launcher)
                    launcher.run()
                }
            }
            finalizeBuild(record, BuildTerminalOutcome.Succeeded)
            notifier.notifyFinal(tracker)
        } catch (exception: Exception) {
            val message = exception.message ?: exception.toString()
            finalizeBuild(record, BuildTerminalOutcome.Failed(message))
            notifier.notifyFinal(tracker)
            throw exception
        }
    }

    private fun foregroundSuccessResponse(
        record: BuildRecord,
        request: BuildRunRequest,
        tracker: BuildProgressTracker,
    ): Map<String, Any?> =
        foregroundResponse(record, request, tracker, BuildProgressTracker.STATUS_SUCCEEDED)

    private fun foregroundFailureResponse(
        record: BuildRecord,
        request: BuildRunRequest,
        tracker: BuildProgressTracker,
    ): Map<String, Any?> =
        foregroundResponse(record, request, tracker, BuildProgressTracker.STATUS_FAILED)

    private fun foregroundResponse(
        record: BuildRecord,
        request: BuildRunRequest,
        tracker: BuildProgressTracker,
        status: String,
    ): Map<String, Any?> {
        val buildSummary = BuildOutputParser.parse(record.streams.stdoutSnapshot().text)
        val response = mutableMapOf<String, Any?>(
            "status" to status,
        )
        BuildOutputParser.outcomeFromStatus(status)?.let { response["outcome"] = it }
        response["buildSummary"] = BuildOutputParser.toResponseMap(buildSummary)
        response.putAll(optionalProgressFields(request.progressOptions, tracker.snapshot()))
        response.putAll(buildResult(record, request.outputLimit))
        return response
    }

    private fun buildStatusResponse(
        record: BuildRecord,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
    ): Map<String, Any?> {
        val progress = record.progressTracker.snapshot()
        val response = mutableMapOf<String, Any?>(
            "buildId" to record.id,
            "kind" to record.kind.name.lowercase(),
            "status" to progress.status,
            "startedAt" to record.startedAt.toString(),
            "finishedAt" to record.finishedAt?.toString(),
            "tasks" to record.tasks,
            "testClasses" to record.testClasses,
        )
        response.putAll(optionalProgressFields(progressOptions, progress))
        if (record.errorMessage != null) {
            response["error"] = record.errorMessage
        }
        if (progress.status != BuildProgressTracker.STATUS_RUNNING) {
            response.putAll(buildResult(record, outputLimit))
            BuildOutputParser.outcomeFromStatus(progress.status)?.let { response["outcome"] = it }
            response["buildSummary"] = BuildOutputParser.toResponseMap(
                BuildOutputParser.parse(record.streams.stdoutSnapshot().text),
            )
        } else {
            response.putAll(limitStreamFields(record.streams.stdoutSnapshot(), outputLimit, "stdout"))
            response.putAll(limitStreamFields(record.streams.stderrSnapshot(), outputLimit, "stderr"))
        }
        return response
    }

    private fun buildResult(record: BuildRecord, outputLimit: OutputLimitOptions): Map<String, Any?> =
        buildMap {
            when (record.kind) {
                BuildKind.TASKS -> put("tasks", record.tasks)
                BuildKind.TESTS -> put("testClasses", record.testClasses)
            }
            putAll(limitStreamFields(record.streams.stdoutSnapshot(), outputLimit, "stdout"))
            putAll(limitStreamFields(record.streams.stderrSnapshot(), outputLimit, "stderr"))
            record.errorMessage?.let { put("error", it) }
        }

    private fun pruneCompletedBuilds() {
        val completed = builds.values
            .filter { it.progressTracker.snapshot().status != BuildProgressTracker.STATUS_RUNNING }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
        if (completed.size <= MAX_RETAINED_BUILDS) {
            return
        }
        completed.drop(MAX_RETAINED_BUILDS).forEach { builds.remove(it.id) }
    }

    private class ProgressNotifier(
        private val exchange: McpSyncServerExchange?,
        private val progressToken: Any?,
    ) {
        fun notifyIfNeeded(tracker: BuildProgressTracker) {
            if (!tracker.shouldNotifyProgress()) {
                return
            }
            sendProgress(tracker, final = false)
            sendLog(tracker)
        }

        fun notifyFinal(tracker: BuildProgressTracker) {
            sendProgress(tracker, final = true)
            sendLog(tracker)
        }

        private fun sendProgress(tracker: BuildProgressTracker, final: Boolean) {
            if (exchange == null || progressToken == null) {
                return
            }
            val snapshot = tracker.snapshot()
            val completed = snapshot.completedTaskCount
            val total = (completed + snapshot.runningTaskCount + snapshot.failedTaskCount).coerceAtLeast(1)
            val message = buildString {
                append(snapshot.currentOperation ?: "Gradle build")
                append(" — completed ")
                append(completed)
                if (snapshot.failedTaskCount > 0) {
                    append(", failed ")
                    append(snapshot.failedTaskCount)
                }
            }
            McpProgressSupport.sendProgress(
                exchange = exchange,
                progressToken = progressToken,
                progress = if (final) total.toDouble() else completed.toDouble(),
                total = total.toDouble(),
                message = message,
            )
        }

        private fun sendLog(tracker: BuildProgressTracker) {
            val snapshot = tracker.snapshot()
            val latest = snapshot.recentEvents.lastOrNull() ?: return
            val level = when (latest.eventType) {
                "TASK_FAIL", "TEST_FAIL", "FAIL" -> McpSchema.LoggingLevel.ERROR
                "TASK_SKIP", "TEST_SKIP" -> McpSchema.LoggingLevel.WARNING
                else -> McpSchema.LoggingLevel.INFO
            }
            McpProgressSupport.sendLog(
                exchange = exchange,
                progressToken = progressToken,
                message = "${latest.eventType}: ${latest.displayName}",
                level = level,
            )
        }
    }

    private data class BuildStart(
        val record: BuildRecord,
        val notifier: ProgressNotifier,
    )

    internal fun seedRunningBuildForTests(record: BuildRecord) {
        builds[record.id] = record
    }

    internal fun seedLastCompletedBuildForTests(snapshot: CompletedBuildSnapshot) {
        lastCompletedBuildSnapshot = snapshot
    }

    internal fun maxConcurrentBackgroundBuilds(): Int = MAX_CONCURRENT_BUILDS

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

    private fun replaceBuildExecutor() {
        executor.shutdownNow()
        executor = newBuildExecutor()
    }

    private fun maxConcurrentBuildsException(): McpException =
        McpException(
            McpErrorCode.BUILD_ALREADY_RUNNING,
            "Maximum concurrent background builds ($MAX_CONCURRENT_BUILDS) reached. " +
                "Poll gradle_get_build_status or wait for a build to finish.",
        )

    companion object {
        private val MAX_CONCURRENT_BUILDS = maxOf(4, Runtime.getRuntime().availableProcessors())
        private const val MAX_RETAINED_BUILDS = 10
    }
}

private fun limitStreamFields(
    snapshot: CapturedStreamSnapshot,
    outputLimit: OutputLimitOptions,
    fieldPrefix: String,
): Map<String, Any?> {
    val limited = OutputLimiter.limit(snapshot.text, outputLimit)
    return mapOf(
        fieldPrefix to limited.text,
        "${fieldPrefix}Truncated" to (limited.truncated || limited.totalChars < snapshot.totalChars),
        "${fieldPrefix}TotalChars" to snapshot.totalChars,
    )
}
