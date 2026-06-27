package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.PersistedBuildArtifacts
import com.example.gradle.mcp.build.persistence.PersistedBuildViewFactory
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class BuildStatusMergerTest {
    @Test
    fun `pickStream prefers memory when it has more characters`() {
        val memory = BuildStatusView.fromRecord(
            recordWithStdout(
                "partial\nBUILD SUCCESSFUL in 2s\n2 actionable tasks: 2 executed\n",
            ),
        )
        val disk = diskView(
            buildId = memory.buildId,
            status = BuildProgressTracker.STATUS_SUCCEEDED,
            stdout = CapturedStreamSnapshot(text = "partial\n", totalChars = "partial\n".length),
            buildSummary = null,
            recordDirectory = "/tmp/record",
        )

        val merged = BuildStatusMerger.merge(memory, disk)

        merged.stdout.text shouldBe memory.stdout.text
        merged.stdout.totalChars shouldBe memory.stdout.totalChars
        (merged.buildSummary as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 2s"
    }

    @Test
    fun `pickStream prefers disk when it has more characters`() {
        val memory = BuildStatusView.fromRecord(
            recordWithStdout("short\n"),
        )
        val disk = diskView(
            buildId = memory.buildId,
            status = BuildProgressTracker.STATUS_SUCCEEDED,
            stdout = CapturedStreamSnapshot(
                text = "BUILD SUCCESSFUL in 1s\n",
                totalChars = "BUILD SUCCESSFUL in 1s\n".length,
            ),
            buildSummary = mapOf("resultLine" to "BUILD SUCCESSFUL in 1s"),
            recordDirectory = "/tmp/record",
        )

        val merged = BuildStatusMerger.merge(memory, disk)

        merged.stdout.text shouldBe disk.stdout.text
        (merged.buildSummary as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 1s"
    }

    @Test
    fun `merge enriches streams when status matches`() {
        val memory = BuildStatusView.fromRecord(
            recordWithStdout("short\n"),
        )
        val disk = diskView(
            buildId = memory.buildId,
            status = BuildProgressTracker.STATUS_FAILED,
            stdout = CapturedStreamSnapshot(
                text = "BUILD FAILED in 1s\n> Task :app:broken FAILED\n",
                totalChars = "BUILD FAILED in 1s\n> Task :app:broken FAILED\n".length,
            ),
            buildSummary = mapOf("resultLine" to "BUILD FAILED in 1s"),
            recordDirectory = "/tmp/record",
        )

        val merged = BuildStatusMerger.merge(memory, disk)

        merged.status shouldBe BuildProgressTracker.STATUS_FAILED
        merged.statusSource shouldBe BuildStatusView.SOURCE_MEMORY
        merged.stdout.text shouldBe disk.stdout.text
        (merged.buildSummary as Map<*, *>)["resultLine"] shouldBe "BUILD FAILED in 1s"
    }

    @Test
    fun `merge keeps failureSummary when stdout lacks result line`() {
        val memory = BuildStatusView.fromRecord(
            recordWithStdout("short\n"),
        )
        val disk = diskView(
            buildId = memory.buildId,
            status = BuildProgressTracker.STATUS_FAILED,
            stdout = CapturedStreamSnapshot(
                text = "> Task :app:broken FAILED\n",
                totalChars = "> Task :app:broken FAILED\n".length,
            ),
            buildSummary = mapOf("resultLine" to "BUILD FAILED in 1s"),
            recordDirectory = "/tmp/record",
        )

        val merged = BuildStatusMerger.merge(memory, disk)

        (merged.buildSummary as Map<*, *>)["failureSummary"] shouldBe listOf(":app:broken")
        (merged.buildSummary as Map<*, *>)["resultLine"] shouldBe null
    }

    @Test
    fun `merge keeps memory progress when both are running`() {
        val memory = BuildStatusView.fromRecord(runningRecord("running-merge"))
        val disk = diskView(
            buildId = "running-merge",
            status = BuildProgressTracker.STATUS_RUNNING,
            stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
            buildSummary = null,
            recordDirectory = "/tmp/record",
        )

        val merged = BuildStatusMerger.merge(memory, disk)

        merged.status shouldBe BuildProgressTracker.STATUS_RUNNING
        merged.statusSource shouldBe BuildStatusView.SOURCE_MEMORY
        merged.recordDirectory shouldBe "/tmp/record"
    }

    @Test
    fun `merge unions problems from disk and memory progress`() {
        val memory = failedView(
            buildId = "failed-merge",
            progress = progressSnapshot(
                failedTaskCount = 1,
                failedTasks = listOf(":app:broken"),
                totalEventCount = 3,
                problems = listOf(
                    BuildProblemSnapshot(label = "From memory", details = "memory-only"),
                ),
            ),
        )
        val disk = failedView(
            buildId = "failed-merge",
            progress = progressSnapshot(
                failedTaskCount = 0,
                totalEventCount = 1,
                problems = listOf(
                    BuildProblemSnapshot(label = "From disk", details = "disk-only"),
                ),
            ),
        )

        val merged = BuildStatusMerger.merge(memory, disk)

        merged.progress?.failedTaskCount shouldBe 1
        merged.progress?.failedTasks shouldBe listOf(":app:broken")
        merged.progress?.totalEventCount shouldBe 3
        merged.progress?.problems.orEmpty() shouldHaveSize 2
        merged.progress?.problems?.map { it.label } shouldBe listOf("From memory", "From disk")
    }

    @Test
    fun `merge unions solutions when disk and memory share the same problem`() {
        val sharedProblem = BuildProblemSnapshot(
            label = "Compilation failed",
            details = "cannot find symbol",
            solutions = listOf("Fix imports"),
        )
        val memory = failedView(
            buildId = "failed-merge",
            progress = progressSnapshot(
                problems = listOf(sharedProblem),
            ),
        )
        val disk = failedView(
            buildId = "failed-merge",
            progress = progressSnapshot(
                problems = listOf(
                    sharedProblem.copy(solutions = listOf("Add dependency")),
                ),
            ),
        )

        val merged = BuildStatusMerger.merge(memory, disk)

        merged.progress?.problems.orEmpty() shouldHaveSize 1
        merged.progress?.problems?.single()?.solutions shouldBe listOf("Fix imports", "Add dependency")
    }

    private fun recordWithStdout(stdout: String): BuildRecord {
        val streams = CapturingStreams()
        streams.appendStdoutForTests(stdout)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Gradle connection closed")
        return BuildRecord(
            id = "merge-build",
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = java.time.Instant.parse("2026-06-14T10:00:00Z"),
            progressTracker = tracker,
            streams = streams,
            projectDirectory = null,
        ).also {
            it.finishedAt = java.time.Instant.parse("2026-06-14T10:01:00Z")
            it.errorMessage = "Gradle connection closed"
        }
    }

    private fun runningRecord(buildId: String): BuildRecord {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        return BuildRecord(
            id = buildId,
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = java.time.Instant.parse("2026-06-14T10:00:00Z"),
            progressTracker = tracker,
            streams = CapturingStreams(),
            projectDirectory = null,
        )
    }

    private fun diskView(
        buildId: String,
        status: String,
        stdout: CapturedStreamSnapshot,
        buildSummary: Map<String, Any?>?,
        recordDirectory: String,
    ): BuildStatusView =
        PersistedBuildViewFactory.fromArtifacts(
            buildId,
            PersistedBuildArtifacts(
                recordDir = File(recordDirectory),
                gradleResult = null,
                mcpResult = null,
                stdout = stdout,
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                events = emptyList(),
            ),
        ).copy(
            status = status,
            buildSummary = buildSummary,
            recordDirectory = recordDirectory,
        )

    private fun failedView(
        buildId: String,
        progress: BuildProgressSnapshot,
    ): BuildStatusView =
        BuildStatusView(
            buildId = buildId,
            kind = "tasks",
            status = BuildProgressTracker.STATUS_FAILED,
            startedAt = "2026-06-14T10:00:00Z",
            finishedAt = "2026-06-14T10:01:00Z",
            tasks = listOf("build"),
            testClasses = emptyList(),
            error = "Build failed",
            outcome = "FAILED",
            buildSummary = null,
            progress = progress,
            progressAvailable = true,
            stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
            stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
            statusSource = BuildStatusView.SOURCE_MEMORY,
            recordDirectory = null,
        )

    private fun progressSnapshot(
        failedTaskCount: Int = 0,
        failedTasks: List<String> = emptyList(),
        totalEventCount: Int = 0,
        problems: List<BuildProblemSnapshot> = emptyList(),
    ): BuildProgressSnapshot =
        BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = null,
            completedTaskCount = 0,
            runningTaskCount = 0,
            failedTaskCount = failedTaskCount,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = failedTasks,
            recentEvents = emptyList(),
            totalEventCount = totalEventCount,
            problems = problems,
        )
}
