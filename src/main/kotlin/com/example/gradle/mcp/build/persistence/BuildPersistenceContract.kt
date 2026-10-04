package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildOutputParser
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.ProgressEventTypes
import java.time.Duration
import java.time.Instant

/**
 * Disk persistence contract for MCP builds under `.gradle/mcp-builds/<buildId>/`.
 *
 * | Artifact | Writer | Role |
 * |----------|--------|------|
 * | `gradle-result.json` | Gradle init script | Authoritative **status** while Gradle owns the build |
 * | `events.ndjson` | Gradle init script | Task/test progress during the build |
 * | `mcp-result.json` | MCP `finalizeBuild` | Terminal **buildSummary**, failed tasks when MCP survives to the end |
 * | `stdout.log` / `stderr.log` | MCP `finalizeBuild` | Captured streams; empty on disk polls until MCP finalizes |
 *
 * Status resolution: gradle terminal > gradle running (while Gradle still emits events) >
 * stale gradle running (MCP terminal, no post-finalize events) > mcp terminal > running.
 * Exception: Gradle `succeeded` yields to MCP `failed` when Gradle's own output reports
 * `BUILD FAILED` — the init script only sees build work, so failures after task execution
 * (e.g. a build service failing on close) never reach `gradle-result.json`.
 * Terminal [buildSummary]: winning terminal source only — MCP summary when MCP is terminal
 * authority; when Gradle is terminal authority, parse `stdout.log` (ignore stale MCP summary).
 */
internal object BuildPersistenceContract {
    internal enum class TerminalStatusSource {
        GRADLE,
        MCP,
        NONE,
    }

    internal data class ResolvedPersistence(
        val status: String,
        val terminalSource: TerminalStatusSource,
    )

    fun resolve(
        gradleResult: GradleBuildResult?,
        mcpResult: McpBuildResult?,
        events: List<DiskBuildEvent> = emptyList(),
        eventsLastModified: Instant? = null,
        stdout: String? = null,
        stderr: String? = null,
    ): ResolvedPersistence {
        val gradleStatus = gradleResult?.status
        if (gradleStatus == BuildProgressTracker.STATUS_SUCCEEDED &&
            mcpResult?.status == BuildProgressTracker.STATUS_FAILED &&
            outputReportsBuildFailed(stdout, stderr, mcpResult)
        ) {
            return ResolvedPersistence(BuildProgressTracker.STATUS_FAILED, TerminalStatusSource.MCP)
        }
        if (gradleStatus == BuildProgressTracker.STATUS_SUCCEEDED ||
            gradleStatus == BuildProgressTracker.STATUS_FAILED ||
            gradleStatus == BuildProgressTracker.STATUS_CANCELLED
        ) {
            return ResolvedPersistence(gradleStatus, TerminalStatusSource.GRADLE)
        }
        if (gradleStatus == BuildProgressTracker.STATUS_RUNNING) {
            val mcpStatus = mcpResult?.status
            if (mcpStatus != null &&
                mcpStatus != BuildProgressTracker.STATUS_RUNNING &&
                isStaleGradleRunning(mcpResult, events, eventsLastModified)
            ) {
                return ResolvedPersistence(mcpStatus, TerminalStatusSource.MCP)
            }
            return ResolvedPersistence(BuildProgressTracker.STATUS_RUNNING, TerminalStatusSource.NONE)
        }
        val mcpStatus = mcpResult?.status
        if (mcpStatus != null && mcpStatus != BuildProgressTracker.STATUS_RUNNING) {
            return ResolvedPersistence(mcpStatus, TerminalStatusSource.MCP)
        }
        val status = gradleStatus ?: mcpStatus ?: BuildProgressTracker.STATUS_RUNNING
        return ResolvedPersistence(status, TerminalStatusSource.NONE)
    }

    /**
     * Gradle's result line from captured [stdout] / [stderr] when available, else the persisted
     * MCP `buildSummary.resultLine` (list summaries do not read the log files).
     */
    private fun outputReportsBuildFailed(stdout: String?, stderr: String?, mcpResult: McpBuildResult): Boolean {
        BuildOutputParser.reportsBuildFailed(stdout, stderr)?.let { return it }
        val resultLine = mcpResult.buildSummary?.get("resultLine") as? String ?: return false
        return BuildOutputParser.isBuildFailedResultLine(resultLine)
    }

    private val staleGradleGracePeriod: Duration = Duration.ofSeconds(30)

    /**
     * Gradle `running` is stale when MCP finalized long ago and Gradle has not appended any
     * event (including init-script [ProgressEventTypes.HEARTBEAT]) at or after MCP's
     * `finishedAt` (daemon likely dead). Pre-finalize task events alone are inconclusive
     * right after disconnect, so a grace period is required.
     *
     * When [events] is empty (Gradle died before its first event, or `events.ndjson` is
     * missing or unreadable), the parsed log gives no timestamp evidence either way; the
     * file's last write ([eventsLastModified]) still proves liveness because a live Gradle
     * keeps appending heartbeats and task events. With no post-finalize event or write the
     * record is reclaimed once `finishedAt` + [staleGradleGracePeriod] has elapsed.
     * `startedAt` anchors the grace check when `finishedAt` is unparseable.
     */
    internal fun isStaleGradleRunning(
        mcpResult: McpBuildResult,
        events: List<DiskBuildEvent>,
        eventsLastModified: Instant? = null,
    ): Boolean {
        if (events.any { it.eventType == ProgressEventTypes.BUILD_FINISHED }) {
            return false
        }
        val mcpFinishedAt = parseInstant(mcpResult.finishedAt)
            ?: parseInstant(mcpResult.startedAt)
            ?: return false
        val postFinalizeActivity = events.any { event ->
            parseInstant(event.timestamp)?.let { it >= mcpFinishedAt } == true
        } || eventsLastModified?.let { it >= mcpFinishedAt } == true
        if (postFinalizeActivity) {
            return false
        }
        return Instant.now().isAfter(mcpFinishedAt.plus(staleGradleGracePeriod))
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    fun resolveError(
        gradleResult: GradleBuildResult?,
        mcpResult: McpBuildResult?,
        terminalSource: TerminalStatusSource,
    ): String? =
        when (terminalSource) {
            TerminalStatusSource.GRADLE -> gradleResult?.failure
            TerminalStatusSource.MCP -> mcpResult?.error ?: gradleResult?.failure
            TerminalStatusSource.NONE -> null
        }

    fun terminalBuildSummary(
        artifacts: PersistedBuildArtifacts,
        terminalSource: TerminalStatusSource,
    ): Map<String, Any?>? =
        when (terminalSource) {
            TerminalStatusSource.MCP -> {
                artifacts.mcpResult?.buildSummary ?: buildSummaryFromStdout(artifacts)
            }
            TerminalStatusSource.GRADLE -> buildSummaryFromStdout(artifacts)
            TerminalStatusSource.NONE -> null
        }

    private fun buildSummaryFromStdout(artifacts: PersistedBuildArtifacts): Map<String, Any?>? =
        BuildOutputParser.summaryFromStdout(artifacts.stdout.text, artifacts.stderr.text)
}
