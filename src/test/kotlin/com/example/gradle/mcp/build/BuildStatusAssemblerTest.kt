package com.example.gradle.mcp.build

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
}
