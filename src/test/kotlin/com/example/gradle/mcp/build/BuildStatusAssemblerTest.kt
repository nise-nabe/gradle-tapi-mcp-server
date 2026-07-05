package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.DownloadProgressSnapshot
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.TEST_ISO_FINISH
import com.example.gradle.mcp.support.TEST_ISO_START
import com.example.gradle.mcp.support.failedTestSnapshot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BuildStatusAssemblerTest {
    @Test
    fun `assemble includes recordDirectory when present on memory status source`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "memory-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_RUNNING,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = null,
                tasks = listOf("build"),
                error = null,
                outcome = null,
                buildSummary = null,
                progress = BuildProgressSnapshot(
                    status = BuildProgressTracker.STATUS_RUNNING,
                    currentOperation = "Gradle tasks: build",
                    completedTaskCount = 0,
                    runningTaskCount = 1,
                    failedTaskCount = 0,
                    completedTasks = emptyList(),
                    runningTasks = listOf(":app:build"),
                    failedTasks = emptyList(),
                    recentEvents = emptyList(),
                    totalEventCount = 1,
                ),
                progressAvailable = true,
                stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                statusSource = BuildStatusView.SOURCE_MEMORY,
                recordDirectory = "/tmp/record",
            ),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(),
        )

        response["statusSource"] shouldBe "memory"
        response["recordDirectory"] shouldBe "/tmp/record"
        response.containsKey("liveProgress") shouldBe false
    }

    @Test
    fun `assemble includes recordDirectory for disk status source`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "disk-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_RUNNING,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = null,
                tasks = listOf("build"),
                error = null,
                outcome = null,
                buildSummary = null,
                progress = null,
                progressAvailable = false,
                stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                statusSource = BuildStatusView.SOURCE_DISK,
                recordDirectory = "/tmp/record",
            ),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(),
        )

        response["statusSource"] shouldBe "disk"
        response["recordDirectory"] shouldBe "/tmp/record"
        response["liveProgress"] shouldBe false
    }

    @Test
    fun `assemble exposes liveProblems without includeProgress when includeProblems is enabled`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "running-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_RUNNING,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = null,
                tasks = listOf("build"),
                error = null,
                outcome = null,
                buildSummary = null,
                progress = BuildProgressSnapshot(
                    status = BuildProgressTracker.STATUS_RUNNING,
                    currentOperation = "Gradle tasks: build",
                    completedTaskCount = 0,
                    runningTaskCount = 1,
                    failedTaskCount = 0,
                    completedTasks = emptyList(),
                    runningTasks = listOf(":app:build"),
                    failedTasks = emptyList(),
                    recentEvents = emptyList(),
                    totalEventCount = 1,
                    liveProblems = listOf(
                        BuildProblemSnapshot(
                            label = "Deprecated API usage",
                            severity = "warning",
                        ),
                    ),
                ),
                progressAvailable = true,
                stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                statusSource = BuildStatusView.SOURCE_MEMORY,
            ),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(includeProblems = true),
        )

        response.containsKey("progress") shouldBe false
        (response["liveProblems"] as List<*>).single().let { problem ->
            (problem as Map<*, *>)["label"] shouldBe "Deprecated API usage"
        }
    }

    @Test
    fun `assemble omits download fields for disk status source even when includeDownloads is enabled`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "disk-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_RUNNING,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = null,
                tasks = listOf("build"),
                error = null,
                outcome = null,
                buildSummary = null,
                progress = BuildProgressSnapshot(
                    status = BuildProgressTracker.STATUS_RUNNING,
                    currentOperation = "Gradle tasks: build",
                    completedTaskCount = 0,
                    runningTaskCount = 0,
                    failedTaskCount = 0,
                    completedTasks = emptyList(),
                    runningTasks = emptyList(),
                    failedTasks = emptyList(),
                    recentEvents = emptyList(),
                    totalEventCount = 0,
                    recentDownloads = listOf(
                        DownloadProgressSnapshot(
                            uri = "https://repo.example.com/foo.jar",
                            status = BuildProgressTracker.DOWNLOAD_STATUS_SUCCEEDED,
                        ),
                    ),
                    activeDownloadCount = 1,
                ),
                progressAvailable = true,
                stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                statusSource = BuildStatusView.SOURCE_DISK,
                recordDirectory = "/tmp/record",
            ),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(includeDownloads = true),
        )

        response.containsKey("activeDownloadCount") shouldBe false
        response.containsKey("recentDownloads") shouldBe false
    }

    @Test
    fun `assemble omits download fields in progress for disk status source even when includeProgress and includeDownloads are enabled`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "disk-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_RUNNING,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = null,
                tasks = listOf("build"),
                error = null,
                outcome = null,
                buildSummary = null,
                progress = BuildProgressSnapshot(
                    status = BuildProgressTracker.STATUS_RUNNING,
                    currentOperation = "Gradle tasks: build",
                    completedTaskCount = 0,
                    runningTaskCount = 0,
                    failedTaskCount = 0,
                    completedTasks = emptyList(),
                    runningTasks = emptyList(),
                    failedTasks = emptyList(),
                    recentEvents = emptyList(),
                    totalEventCount = 0,
                    recentDownloads = listOf(
                        DownloadProgressSnapshot(
                            uri = "https://repo.example.com/foo.jar",
                            status = BuildProgressTracker.DOWNLOAD_STATUS_SUCCEEDED,
                        ),
                    ),
                    activeDownloadCount = 1,
                ),
                progressAvailable = true,
                stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                statusSource = BuildStatusView.SOURCE_DISK,
                recordDirectory = "/tmp/record",
            ),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(includeProgress = true, includeDownloads = true),
        )

        val progress = response["progress"] as Map<*, *>
        progress.containsKey("activeDownloadCount") shouldBe false
        progress.containsKey("recentDownloads") shouldBe false
    }

    @Test
    fun `assemble includes problems for failed foreground builds without includeProgress`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "failed-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_FAILED,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = "2026-06-14T10:01:00Z",
                tasks = listOf("build"),
                error = "Build failed",
                outcome = "FAILED",
                buildSummary = mapOf("failureSummary" to listOf(":app:compileJava")),
                progress = BuildProgressSnapshot(
                    status = BuildProgressTracker.STATUS_FAILED,
                    currentOperation = null,
                    completedTaskCount = 0,
                    runningTaskCount = 0,
                    failedTaskCount = 1,
                    completedTasks = emptyList(),
                    runningTasks = emptyList(),
                    failedTasks = listOf(":app:compileJava"),
                    recentEvents = emptyList(),
                    totalEventCount = 1,
                    problems = listOf(
                        BuildProblemSnapshot(
                            label = "Compilation failed",
                            severity = "error",
                        ),
                    ),
                ),
                progressAvailable = true,
                stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                statusSource = BuildStatusView.SOURCE_MEMORY,
            ),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(),
            style = BuildStatusResponseStyle.FOREGROUND,
        )

        response["buildSummary"] shouldBe mapOf("failureSummary" to listOf(":app:compileJava"))
        (response["problems"] as List<*>).single().let { problem ->
            (problem as Map<*, *>)["label"] shouldBe "Compilation failed"
        }
        response.containsKey("progress") shouldBe false
    }

    @Test
    fun `assemble includes failedTests summary when requested`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "failed-tests-build",
                kind = "tests",
                status = BuildProgressTracker.STATUS_FAILED,
                startedAt = TEST_ISO_START,
                finishedAt = TEST_ISO_FINISH,
                tasks = listOf("test"),
                selection = null,
                error = "Test failed",
                outcome = "FAILED",
                buildSummary = mapOf("failureSummary" to listOf("1 test failed")),
                progress = BuildProgressSnapshot(
                    status = BuildProgressTracker.STATUS_FAILED,
                    currentOperation = null,
                    completedTaskCount = 0,
                    runningTaskCount = 0,
                    failedTaskCount = 1,
                    completedTasks = emptyList(),
                    runningTasks = emptyList(),
                    failedTasks = listOf(":test"),
                    recentEvents = emptyList(),
                    totalEventCount = 1,
                    failedTests = listOf(
                        failedTestSnapshot(
                            className = "com.example.FooTest",
                            methodName = "bar",
                            failureMessage = "boom",
                        ),
                    ),
                ),
                progressAvailable = true,
                stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
                stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
                statusSource = BuildStatusView.SOURCE_MEMORY,
            ),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(includeTestDetails = true),
        )

        ((((response["failedTests"] as List<*>).single() as Map<*, *>)["methodName"])) shouldBe "bar"
    }
}
