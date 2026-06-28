package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.PersistedBuildViewFactory
import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.McpProgressSupport
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.ProjectConnection
import java.io.File
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
    private val buildRecordStore: BuildRecordStore = BuildRecordStore(),
) {
    private val lifecycleLock = Any()
    private var executor: ExecutorService = newBuildExecutor()
    private val builds = ConcurrentHashMap<String, BuildRecord>()
    private val lastCompletedBuildSnapshots = ConcurrentHashMap<String, CompletedBuildSnapshot>()

    fun startBackground(
        request: BuildRunRequest,
        exchange: McpSyncServerExchange?,
        progressToken: Any?,
    ): Map<String, Any?> {
        connectionManager.requireConnection(request.projectDirectory)

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

        return buildMap {
            put("buildId", buildId)
            put("status", BuildProgressTracker.STATUS_RUNNING)
            put("kind", request.kind.name.lowercase())
            put("tasks", request.tasks)
            put("testClasses", request.testClasses)
            putTestRunSelection(
                testMethods = request.testMethods,
                taskPath = request.taskPath,
                includePatterns = request.includePatterns,
            )
            put(
                "message",
                "Build started in background. Poll gradle_get_build_status with this buildId.",
            )
        }
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
            BuildStatusAssembler.assemble(
                view = BuildStatusView.fromRecord(start.record),
                outputLimit = request.outputLimit,
                progressOptions = request.progressOptions,
                style = BuildStatusResponseStyle.FOREGROUND,
            )
        } catch (_: Exception) {
            BuildStatusAssembler.assemble(
                view = BuildStatusView.fromRecord(start.record),
                outputLimit = request.outputLimit,
                progressOptions = request.progressOptions,
                style = BuildStatusResponseStyle.FOREGROUND,
            )
        } finally {
            pruneCompletedBuilds()
        }
    }

    fun cancelBuild(buildId: String, projectDirectoryHint: File? = null): Map<String, Any?> {
        val record = builds[buildId]
        if (record == null) {
            throw McpException(McpErrorCode.INVALID_ARGUMENT, "Build not found: $buildId")
        }
        projectDirectoryHint?.let { hint ->
            val recordProject = record.projectDirectory
            if (recordProject != null && !ProjectDirectoryResolver.sameProject(recordProject, hint)) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Build $buildId does not belong to project ${hint.path}",
                )
            }
        }
        val status = record.progressTracker.snapshot().status
        if (status != BuildProgressTracker.STATUS_RUNNING) {
            return mapOf(
                "buildId" to buildId,
                "status" to status,
                "message" to "Build is not running.",
            )
        }
        record.cancellationTokenSource.cancel()
        return mapOf(
            "buildId" to buildId,
            "status" to BuildProgressTracker.STATUS_RUNNING,
            "message" to "Cancellation requested. Poll gradle_get_build_status until status is no longer running.",
        )
    }

    fun status(
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        projectDirectoryHint: File? = null,
    ): Map<String, Any?> {
        val record = builds[buildId]
        projectDirectoryHint?.let { hint ->
            val recordProject = record?.projectDirectory
            if (record != null && recordProject != null &&
                !ProjectDirectoryResolver.sameProject(recordProject, hint)
            ) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Build $buildId does not belong to project ${hint.path}",
                )
            }
        }
        val projectDirectory = record?.projectDirectory?.let(::File)
            ?: projectDirectoryHint
            ?: connectionManager.defaultProjectDirectory()
        val artifacts = if (shouldLoadDiskArtifacts(record)) {
            projectDirectory?.let { buildRecordStore.loadArtifacts(it, buildId) }
        } else {
            null
        }

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
        return BuildStatusAssembler.assemble(view, outputLimit, progressOptions)
    }

    fun listBuilds(projectDirectoryHint: File?, limit: Int): Map<String, Any?> {
        val cappedLimit = limit.coerceIn(1, MAX_LIST_BUILDS)
        val projectDirectory = resolveProjectDirectory(projectDirectoryHint)
        val projectPath = projectDirectory?.absolutePath

        val entries = LinkedHashMap<String, BuildListEntry>()
        builds.values
            .asSequence()
            .filter { record -> record.matchesProject(projectDirectory) }
            .forEach { record ->
                val snapshot = record.progressTracker.snapshot()
                entries[record.id] = BuildListEntry(
                    buildId = record.id,
                    status = snapshot.status,
                    kind = record.kind.name.lowercase(),
                    tasks = record.tasks,
                    testClasses = record.testClasses,
                    testMethods = record.testMethods,
                    taskPath = record.taskPath,
                    includePatterns = record.includePatterns,
                    projectDirectory = record.projectDirectory,
                    startedAt = record.startedAt.toString(),
                    finishedAt = record.finishedAt?.toString(),
                    outcome = BuildOutputParser.outcomeFromStatus(snapshot.status),
                    recordSource = "memory",
                )
            }

        val totalAvailable = if (projectDirectory != null) {
            val diskBuildIds = buildRecordStore.listBuildIds(projectDirectory)
            entries.size + diskBuildIds.count { it !in entries }
        } else {
            entries.size
        }

        if (projectDirectory != null) {
            val diskCandidates = buildRecordStore.listBuildSortEntries(projectDirectory)
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
                    buildRecordStore.loadListSummary(projectDirectory, candidate.buildId)?.let { summary ->
                        entries[candidate.buildId] = summary
                    }
                }
        }

        val sorted = entries.values.sortedByDescending { it.sortInstant() }
        val limited = sorted.take(cappedLimit)
        return buildMap {
            put("builds", limited.map { it.toResponseMap() })
            projectPath?.let { put("projectDirectory", it) }
            put("totalAvailable", totalAvailable)
            put("truncated", totalAvailable > cappedLimit)
        }
    }

    private fun resolveProjectDirectory(hint: File?): File? =
        hint
            ?: connectionManager.defaultProjectDirectory()
            ?: ProjectDirectoryResolver.workspaceFromEnvironment()

    private fun shouldLoadDiskArtifacts(record: BuildRecord?): Boolean =
        record == null || record.progressTracker.snapshot().status != BuildProgressTracker.STATUS_RUNNING

    fun hasActiveBuild(projectDirectory: File? = null): Boolean =
        builds.values.any { record ->
            record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING &&
                record.matchesProject(projectDirectory)
        }

    fun resetBuildState(reason: String, projectDirectory: File? = null) {
        synchronized(lifecycleLock) {
            markRunningBuildsCancelled(reason, projectDirectory)
            if (shouldReplaceExecutor(projectDirectory)) {
                replaceBuildExecutor()
            }
        }
    }

    fun onDisconnect(projectDirectory: File? = null) {
        resetBuildState("Gradle connection closed", projectDirectory)
    }

    private fun shouldReplaceExecutor(projectDirectory: File?): Boolean =
        projectDirectory == null || connectionManager.connectedProjectDirectories().isEmpty()

    fun shutdown() {
        val executorToAwait = synchronized(lifecycleLock) {
            markRunningBuildsCancelled("Server shutting down")
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

    private fun markRunningBuildsCancelled(reason: String, projectDirectory: File? = null) {
        builds.values
            .filter { record ->
                record.progressTracker.snapshot().status == BuildProgressTracker.STATUS_RUNNING &&
                    record.matchesProject(projectDirectory)
            }
            .forEach { record ->
                record.cancellationTokenSource.cancel()
                finalizeBuild(record, BuildTerminalOutcome.Cancelled(reason))
            }
    }

    private sealed interface BuildTerminalOutcome {
        data object Succeeded : BuildTerminalOutcome

        data class Failed(val message: String) : BuildTerminalOutcome

        data class Cancelled(val message: String) : BuildTerminalOutcome
    }

    private fun finalizeBuild(record: BuildRecord, outcome: BuildTerminalOutcome): Boolean {
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
        if (record.finishedAt == null) {
            record.finishedAt = Instant.now()
        }
        rememberCompletedBuild(record, outcome)
        buildRecordStore.writeMcpResult(record, record.progressTracker.snapshot())
        return true
    }

    internal fun lastCompletedBuildSnapshot(projectDirectory: File): CompletedBuildSnapshot? =
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(projectDirectory)]

    private fun rememberCompletedBuild(record: BuildRecord, outcome: BuildTerminalOutcome) {
        val buildOutcome = when (outcome) {
            BuildTerminalOutcome.Succeeded -> "SUCCESS"
            is BuildTerminalOutcome.Failed -> "FAILED"
            is BuildTerminalOutcome.Cancelled -> "CANCELLED"
        }
        val projectDirectory = record.projectDirectory ?: return
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(File(projectDirectory))] =
            CompletedBuildSnapshot(
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
            testMethods = request.testMethods,
            taskPath = request.taskPath,
            includePatterns = request.includePatterns,
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
            projectDirectory = request.projectDirectory.absolutePath,
        )
        return BuildStart(record, notifier)
    }

    private fun runBuild(
        record: BuildRecord,
        request: BuildRunRequest,
        notifier: ProgressNotifier,
    ) {
        try {
            connectionManager.withConnection(request.projectDirectory) { connection ->
                runBuild(record, request, connection, record.streams, record.progressTracker, notifier)
            }
        } catch (exception: Exception) {
            finalizeBuild(record, terminalOutcomeFor(exception))
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
            BuildKind.TESTS -> describeTestOperation(request)
        }
        tracker.markStarting(operationLabel)
        notifier.notifyIfNeeded(tracker)

        try {
            when (request.kind) {
                BuildKind.TASKS -> {
                    val launcher = connection.newBuild()
                        .forTasks(*request.tasks.toTypedArray())
                    configureLauncher(launcher, record, request, streams, tracker)
                    launcher.run()
                }
                BuildKind.TESTS -> {
                    val launcher = configureTestLauncher(connection.newTestLauncher(), request)
                    configureLauncher(launcher, record, request, streams, tracker)
                    launcher.run()
                }
            }
            finalizeBuild(record, BuildTerminalOutcome.Succeeded)
            notifier.notifyFinal(tracker)
        } catch (exception: Exception) {
            val outcome = terminalOutcomeFor(exception)
            finalizeBuild(record, outcome)
            notifier.notifyFinal(tracker)
            throw exception
        }
    }

    private fun terminalOutcomeFor(exception: Exception): BuildTerminalOutcome =
        if (exception is BuildCancelledException) {
            BuildTerminalOutcome.Cancelled(exception.message ?: "Build cancelled")
        } else {
            BuildTerminalOutcome.Failed(exception.message ?: exception.toString())
        }

    private fun configureLauncher(
        launcher: ConfigurableLauncher<*>,
        record: BuildRecord,
        request: BuildRunRequest,
        streams: CapturingStreams,
        tracker: BuildProgressTracker,
    ) {
        GradleArgumentPolicy.requireNoInitScript(request.arguments)
        val persistenceArguments = record.projectDirectory
            ?.let { buildRecordStore.launcherArguments(File(it), record.id) }
            .orEmpty()
        launcher.addArguments(*(request.arguments + persistenceArguments).toTypedArray())
        launcher.addJvmArguments(*request.jvmArguments.toTypedArray())
        launcher.withCancellationToken(record.cancellationTokenSource.token())
        launcher.withDetailedFailure()
        tracker.configureLauncher(launcher)
        streams.applyTo(launcher)
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
                "TASK_FAIL", "TEST_FAIL", "CONFIG_FAIL", "FAIL" -> McpSchema.LoggingLevel.ERROR
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
        val projectDirectory = snapshot.projectDirectory ?: return
        lastCompletedBuildSnapshots[ProjectDirectoryResolver.canonicalKey(File(projectDirectory))] = snapshot
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
        internal const val DEFAULT_LIST_BUILDS = 20
        internal const val MAX_LIST_BUILDS = 100
    }
}
