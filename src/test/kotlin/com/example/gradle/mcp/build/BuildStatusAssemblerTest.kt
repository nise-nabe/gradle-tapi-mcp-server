package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BuildStatusAssemblerTest {
    @Test
    fun `assemble omits recordDirectory for memory status source`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "memory-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_RUNNING,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = null,
                tasks = listOf("build"),
                testClasses = emptyList(),
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
        response.containsKey("recordDirectory") shouldBe false
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
                testClasses = emptyList(),
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
    fun `assemble includes problems for failed foreground builds without includeProgress`() {
        val response = BuildStatusAssembler.assemble(
            view = BuildStatusView(
                buildId = "failed-build",
                kind = "tasks",
                status = BuildProgressTracker.STATUS_FAILED,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = "2026-06-14T10:01:00Z",
                tasks = listOf("build"),
                testClasses = emptyList(),
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
}
