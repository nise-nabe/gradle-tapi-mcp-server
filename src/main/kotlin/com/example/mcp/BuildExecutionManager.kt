package com.example.mcp

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.gradle.tooling.ProjectConnection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class BuildRunRequest(
    val kind: BuildKind,
    val tasks: List<String> = emptyList(),
    val testClasses: List<String> = emptyList(),
    val arguments: List<String> = emptyList(),
    val jvmArguments: List<String> = emptyList(),
    val outputLimit: OutputLimitOptions = OutputLimitOptions(),
)

enum class BuildKind {
    TASKS,
    TESTS,
}

data class BuildRecord(
    val id: String,
    val kind: BuildKind,
    val tasks: List<String>,
    val testClasses: List<String>,
    val startedAt: Instant,
    val progressTracker: BuildProgressTracker,
    val streams: CapturingStreams,
) {
    @Volatile
    var finishedAt: Instant? = null

    @Volatile
    var errorMessage: String? = null
}

class BuildExecutionManager(
    private val connectionManager: GradleConnectionManager,
) {
    private val lifecycleLock = Any()
    private var executor: ExecutorService = newBuildExecutor()
    private val builds = ConcurrentHashMap<String, BuildRecord>()
    private val activeBuildId = AtomicReference<String?>(null)
    private val buildSlot = AtomicBoolean(false)

    fun startBackground(
        request: BuildRunRequest,
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
    ): Map<String, Any?> {
        val buildId: String
        val record: BuildRecord
        val notifier = ProgressNotifier(exchange, progressToken)
        synchronized(lifecycleLock) {
            if (!buildSlot.compareAndSet(false, true)) {
                val activeId = activeBuildId.get()
                error(
                    "Another build is already running" +
                        (activeId?.let { " (buildId=$it)" } ?: "") +
                        ". Call gradle_get_build_status first.",
                )
            }

            try {
                connectionManager.withConnection { }
            } catch (exception: Exception) {
                buildSlot.set(false)
                throw exception
            }

            buildId = UUID.randomUUID().toString()
            val streams = CapturingStreams()
            lateinit var tracker: BuildProgressTracker
            tracker = BuildProgressTracker(onUpdate = { notifier.notifyIfNeeded(tracker) })
            record = BuildRecord(
                id = buildId,
                kind = request.kind,
                tasks = request.tasks,
                testClasses = request.testClasses,
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = streams,
            )
            try {
                builds[buildId] = record
                activeBuildId.set(buildId)
                pruneCompletedBuilds()
                executor.execute {
                    runBuild(record, request, notifier)
                }
            } catch (exception: Exception) {
                builds.remove(buildId)
                if (activeBuildId.compareAndSet(buildId, null)) {
                    buildSlot.set(false)
                }
                finalizeBuild(record, BuildTerminalOutcome.Failed(exception.message ?: exception.toString()))
                throw exception
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
        synchronized(lifecycleLock) {
            if (!buildSlot.compareAndSet(false, true)) {
                val activeId = activeBuildId.get()
                error(
                    "Another build is already running" +
                        (activeId?.let { " (buildId=$it)" } ?: "") +
                        ". Call gradle_get_build_status first.",
                )
            }
        }

        val buildId = "foreground"
        val streams = CapturingStreams()
        val notifier = ProgressNotifier(exchange, progressToken)
        lateinit var tracker: BuildProgressTracker
        tracker = BuildProgressTracker(onUpdate = { notifier.notifyIfNeeded(tracker) })
        val record = BuildRecord(
            id = buildId,
            kind = request.kind,
            tasks = request.tasks,
            testClasses = request.testClasses,
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
        )

        try {
            runBuild(record, request, connection, streams, tracker, notifier)
            return foregroundSuccessResponse(record, request.outputLimit, tracker)
        } catch (exception: Exception) {
            return foregroundFailureResponse(record, request.outputLimit, tracker)
        } finally {
            synchronized(lifecycleLock) {
                buildSlot.set(false)
            }
        }
    }

    fun status(buildId: String?, outputLimit: OutputLimitOptions): Map<String, Any?> {
        val resolvedId = resolveBuildId(buildId)
            ?: return mapOf("status" to "not_found", "message" to "No matching build found.")
        val record = builds[resolvedId]
            ?: return mapOf("status" to "not_found", "buildId" to resolvedId)

        return buildStatusResponse(record, outputLimit)
    }

    fun hasActiveBuild(): Boolean = buildSlot.get()

    fun resetBuildState(reason: String) {
        synchronized(lifecycleLock) {
            markRunningBuildsFailed(reason)
            activeBuildId.set(null)
            buildSlot.set(false)
            replaceBuildExecutor()
        }
    }

    fun onDisconnect() {
        resetBuildState("Gradle connection closed")
    }

    fun shutdown() {
        val executorToAwait = synchronized(lifecycleLock) {
            markRunningBuildsFailed("Server shutting down")
            activeBuildId.set(null)
            buildSlot.set(false)
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
            is BuildTerminalOutcome.Failed -> {
                record.progressTracker.markFailed(outcome.message)
                if (record.errorMessage == null) {
                    record.errorMessage = outcome.message
                }
            }
        }
        if (record.finishedAt == null) {
            record.finishedAt = Instant.now()
        }
        return true
    }

    private fun resolveBuildId(buildId: String?): String? {
        if (!buildId.isNullOrBlank()) {
            return buildId
        }
        activeBuildId.get()?.let { return it }
        return builds.values.maxByOrNull { it.startedAt }?.id
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
            releaseBuildSlotIfActive(record)
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
        outputLimit: OutputLimitOptions,
        tracker: BuildProgressTracker,
    ): Map<String, Any?> =
        buildResult(record, outputLimit) +
            mapOf(
                "status" to BuildProgressTracker.STATUS_SUCCEEDED,
                "progress" to tracker.snapshot().toResponseMap(),
            )

    private fun foregroundFailureResponse(
        record: BuildRecord,
        outputLimit: OutputLimitOptions,
        tracker: BuildProgressTracker,
    ): Map<String, Any?> =
        buildResult(record, outputLimit) +
            mapOf(
                "status" to BuildProgressTracker.STATUS_FAILED,
                "progress" to tracker.snapshot().toResponseMap(),
            )

    private fun buildStatusResponse(record: BuildRecord, outputLimit: OutputLimitOptions): Map<String, Any?> {
        val progress = record.progressTracker.snapshot()
        val response = mutableMapOf<String, Any?>(
            "buildId" to record.id,
            "kind" to record.kind.name.lowercase(),
            "status" to progress.status,
            "startedAt" to record.startedAt.toString(),
            "finishedAt" to record.finishedAt?.toString(),
            "tasks" to record.tasks,
            "testClasses" to record.testClasses,
            "progress" to progress.toResponseMap(),
        )
        if (record.errorMessage != null) {
            response["error"] = record.errorMessage
        }
        if (progress.status != BuildProgressTracker.STATUS_RUNNING) {
            response.putAll(buildResult(record, outputLimit))
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

    internal fun seedRunningBuildForTests(record: BuildRecord) {
        synchronized(lifecycleLock) {
            builds[record.id] = record
            activeBuildId.set(record.id)
            buildSlot.set(true)
        }
    }

    internal fun releaseBuildSlotIfActive(record: BuildRecord) {
        synchronized(lifecycleLock) {
            if (activeBuildId.compareAndSet(record.id, null)) {
                buildSlot.set(false)
            }
        }
    }

    private fun newBuildExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "gradle-build-runner").apply { isDaemon = true }
        }

    private fun replaceBuildExecutor() {
        executor.shutdownNow()
        executor = newBuildExecutor()
    }

    companion object {
        private const val MAX_RETAINED_BUILDS = 5
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

private fun BuildProgressSnapshot.toResponseMap(): Map<String, Any?> =
    mapOf(
        "status" to status,
        "currentOperation" to currentOperation,
        "completedTaskCount" to completedTaskCount,
        "runningTaskCount" to runningTaskCount,
        "failedTaskCount" to failedTaskCount,
        "completedTasks" to completedTasks,
        "runningTasks" to runningTasks,
        "failedTasks" to failedTasks,
        "recentEvents" to recentEvents.map { event ->
            mapOf(
                "timestamp" to event.timestamp,
                "eventType" to event.eventType,
                "displayName" to event.displayName,
                "outcome" to event.outcome,
            )
        },
        "totalEventCount" to totalEventCount,
    )
