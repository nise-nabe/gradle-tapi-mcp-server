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
    ): ResolvedPersistence {
        val gradleStatus = gradleResult?.status
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
                isStaleGradleRunning(mcpResult, events)
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

    private val staleGradleGracePeriod: Duration = Duration.ofSeconds(30)

    /**
     * Gradle `running` is stale when MCP finalized long ago and Gradle has not appended any
     * event (including init-script [ProgressEventTypes.HEARTBEAT]) at or after MCP's
     * `finishedAt` (daemon likely dead). Pre-finalize task events alone are inconclusive
     * right after disconnect, so a grace period is required.
     */
    internal fun isStaleGradleRunning(mcpResult: McpBuildResult, events: List<DiskBuildEvent>): Boolean {
        if (events.isEmpty()) {
            return false
        }
        if (events.any { it.eventType == ProgressEventTypes.BUILD_FINISHED }) {
            return false
        }
        val mcpFinishedAt = parseInstant(mcpResult.finishedAt) ?: return false
        if (events.any { event -> parseInstant(event.timestamp)?.let { it >= mcpFinishedAt } == true }) {
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
        BuildOutputParser.summaryFromStdout(artifacts.stdout.text)
}
