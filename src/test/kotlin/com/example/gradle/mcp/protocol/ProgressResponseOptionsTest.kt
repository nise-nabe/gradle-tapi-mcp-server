package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.BuildStatusView
import com.example.gradle.mcp.build.DownloadProgressSnapshot
import com.example.gradle.mcp.build.ProgressEventSnapshot
import com.example.gradle.mcp.build.ProgressEventTypes
import com.example.gradle.mcp.build.TestProgressDetailsSnapshot
import com.example.gradle.mcp.support.failedTestSnapshot
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProgressResponseOptionsTest {
    @Test
    fun `fromArgs defaults includeProgress to false`() {
        val options = ProgressResponseOptions.fromArgs(emptyMap())

        options.includeProgress.shouldBeFalse()
        options.includeTestDetails.shouldBeFalse()
    }

    @Test
    fun `fromArgs defaults includeDownloads to false`() {
        val options = ProgressResponseOptions.fromArgs(emptyMap())
        options.includeDownloads.shouldBeFalse()
    }

    @Test
    fun `fromArgs defaults includeProblems to false`() {
        val options = ProgressResponseOptions.fromArgs(emptyMap())
        options.includeProblems.shouldBeFalse()
    }

    @Test
    fun `optionalProgressFields omits progress unless requested`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_SUCCEEDED,
            currentOperation = "build",
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 0,
            completedTasks = listOf("build"),
            runningTasks = emptyList(),
            failedTasks = emptyList(),
            recentEvents = emptyList(),
            totalEventCount = 0,
        )

        optionalProgressFields(ProgressResponseOptions(), snapshot) shouldBe emptyMap<String, Any?>()
        optionalProgressFields(ProgressResponseOptions(includeProgress = true), snapshot)["progress"]
            .let { it as Map<*, *> }["status"] shouldBe "succeeded"
    }

    @Test
    fun `optionalDownloadFields exposes downloads without includeProgress`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "download",
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
                    bytesDownloaded = 1024L,
                ),
            ),
            activeDownloadCount = 0,
        )
        val fields = optionalDownloadFields(
            ProgressResponseOptions(includeDownloads = true),
            snapshot,
            BuildStatusView.SOURCE_MEMORY,
        )
        fields["activeDownloadCount"] shouldBe 0
        (fields["recentDownloads"] as List<*>).single().let { d ->
            (d as Map<*, *>)["uri"] shouldBe "https://repo.example.com/foo.jar"
        }
    }


    @Test
    fun `optionalDownloadFields omits downloads for disk status source`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "download",
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
        )

        optionalDownloadFields(
            ProgressResponseOptions(includeDownloads = true),
            snapshot,
            BuildStatusView.SOURCE_DISK,
        ) shouldBe emptyMap()
    }

    @Test
    fun `optionalProgressFields exposes liveProblems when includeProblems is enabled`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "build",
            completedTaskCount = 0,
            runningTaskCount = 1,
            failedTaskCount = 0,
            completedTasks = emptyList(),
            runningTasks = listOf(":compileJava"),
            failedTasks = emptyList(),
            recentEvents = emptyList(),
            totalEventCount = 1,
            liveProblems = listOf(
                BuildProblemSnapshot(
                    label = "Deprecated API usage",
                    severity = "warning",
                ),
            ),
        )

        optionalProgressFields(ProgressResponseOptions(), snapshot).containsKey("liveProblems") shouldBe false
        (optionalProgressFields(ProgressResponseOptions(includeProblems = true), snapshot)["liveProblems"] as List<*>)
            .single()
            .let { problem ->
                (problem as Map<*, *>)["label"] shouldBe "Deprecated API usage"
            }
    }

    @Test
    fun `optionalProgressFields caps liveProblems with most recent entries`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "build",
            completedTaskCount = 0,
            runningTaskCount = 1,
            failedTaskCount = 0,
            completedTasks = emptyList(),
            runningTasks = listOf(":compileJava"),
            failedTasks = emptyList(),
            recentEvents = emptyList(),
            totalEventCount = 1,
            liveProblems = (1..ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE + 1).map { index ->
                BuildProblemSnapshot(
                    label = "warning-$index",
                    severity = "warning",
                )
            },
        )

        val liveProblems = optionalProgressFields(
            ProgressResponseOptions(includeProblems = true),
            snapshot,
        )["liveProblems"] as List<*>

        liveProblems.size shouldBe ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE
        (liveProblems.first() as Map<*, *>)["label"] shouldBe "warning-2"
        (liveProblems.last() as Map<*, *>)["label"] shouldBe
            "warning-${ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE + 1}"
    }

    @Test
    fun `toResponseMap caps completed tasks and recent events`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "build",
            completedTaskCount = 25,
            runningTaskCount = 0,
            failedTaskCount = 0,
            completedTasks = (1..25).map { "task-$it" },
            runningTasks = emptyList(),
            failedTasks = emptyList(),
            recentEvents = (1..15).map {
                ProgressEventSnapshot("t", "TASK_SUCCESS", "task-$it")
            },
            totalEventCount = 15,
        )

        val response = snapshot.toResponseMap(ProgressResponseOptions())

        (response["completedTasks"] as List<*>).size shouldBe 20
        (response["recentEvents"] as List<*>).size shouldBe 10
        (response["completedTasks"] as List<*>).last() shouldBe "task-25"
        ((response["recentEvents"] as List<*>).last() as Map<*, *>)["displayName"] shouldBe "task-15"
    }

    @Test
    fun `toResponseMap includes downloads when requested`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "download",
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
                    bytesDownloaded = 1024L,
                ),
            ),
            activeDownloadCount = 1,
        )

        val response = snapshot.toResponseMap(ProgressResponseOptions(), includeDownloads = true)

        response["activeDownloadCount"] shouldBe 1
        (response["recentDownloads"] as List<*>).single().let { d ->
            (d as Map<*, *>)["uri"] shouldBe "https://repo.example.com/foo.jar"
        }
    }

    @Test
    fun `toResponseMap includes structured test metadata only when requested`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "test",
            completedTaskCount = 0,
            runningTaskCount = 1,
            failedTaskCount = 0,
            completedTasks = emptyList(),
            runningTasks = listOf("com.example.FooTest.bar"),
            failedTasks = emptyList(),
            recentEvents = listOf(
                ProgressEventSnapshot(
                    timestamp = "2026-06-29T22:00:00Z",
                    eventType = ProgressEventTypes.TEST_START,
                    displayName = "com.example.FooTest.bar",
                    testDetails = TestProgressDetailsSnapshot(
                        className = "com.example.FooTest",
                        methodName = "bar",
                        sourceType = "file",
                        sourcePath = "src/test/kotlin/com/example/FooTest.kt",
                        sourceLine = 12,
                    ),
                ),
            ),
            totalEventCount = 1,
        )

        val withoutDetails = snapshot.toResponseMap(ProgressResponseOptions())
        val withDetails = snapshot.toResponseMap(ProgressResponseOptions(includeTestDetails = true))

        (((withoutDetails["recentEvents"] as List<*>).single() as Map<*, *>).containsKey("test")) shouldBe false
        ((((withDetails["recentEvents"] as List<*>).single() as Map<*, *>)["test"] as Map<*, *>)["className"]) shouldBe
            "com.example.FooTest"
    }

    @Test
    fun `terminalFailureFields includes problems for failed builds without includeProgress`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = null,
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 1,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = listOf(":app:compileJava"),
            recentEvents = emptyList(),
            totalEventCount = 2,
            problems = listOf(
                BuildProblemSnapshot(
                    label = "Compilation failed",
                    details = "cannot find symbol",
                    severity = "error",
                ),
            ),
        )

        val fields = terminalFailureFields(snapshot, ProgressResponseOptions())

        fields["failedTaskCount"] shouldBe 1
        fields["failedTasks"] shouldBe listOf(":app:compileJava")
        (fields["problems"] as List<*>).single().let { problem ->
            (problem as Map<*, *>)["label"] shouldBe "Compilation failed"
        }
    }

    @Test
    fun `terminalFailureFields merges liveProblems when includeProblems is enabled`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = null,
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 1,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = listOf(":app:compileJava"),
            recentEvents = emptyList(),
            totalEventCount = 2,
            problems = listOf(
                BuildProblemSnapshot(
                    label = "Compilation failed",
                    details = "cannot find symbol",
                    severity = "error",
                ),
            ),
            liveProblems = listOf(
                BuildProblemSnapshot(
                    label = "Deprecated API usage",
                    severity = "warning",
                ),
                BuildProblemSnapshot(
                    label = "Compilation failed",
                    details = "cannot find symbol",
                    severity = "error",
                ),
            ),
        )

        val fields = terminalFailureFields(snapshot, ProgressResponseOptions(includeProblems = true))

        (fields["problems"] as List<*>).map { (it as Map<*, *>)["label"] } shouldBe
            listOf("Compilation failed", "Deprecated API usage")
    }

    @Test
    fun `terminalFailureFields prioritizes errors when capping problems`() {
        val warnings = (1..ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE).map { index ->
            BuildProblemSnapshot(label = "warning-$index", severity = "warning")
        }
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = null,
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 1,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = listOf(":app:compileJava"),
            recentEvents = emptyList(),
            totalEventCount = 2,
            problems = listOf(
                BuildProblemSnapshot(
                    label = "Compilation failed",
                    details = "cannot find symbol",
                    severity = "error",
                ),
            ) + warnings,
        )

        val fields = terminalFailureFields(snapshot, ProgressResponseOptions())

        val labels = (fields["problems"] as List<*>).map { (it as Map<*, *>)["label"] }
        labels shouldHaveSize ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE
        labels shouldContain "Compilation failed"
        labels.last() shouldBe "warning-${ProgressResponseOptions.MAX_PROBLEMS_IN_RESPONSE}"
    }

    @Test
    fun `terminalFailureFields omits problems for succeeded builds`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_SUCCEEDED,
            currentOperation = null,
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 0,
            completedTasks = listOf("build"),
            runningTasks = emptyList(),
            failedTasks = emptyList(),
            recentEvents = emptyList(),
            totalEventCount = 1,
            problems = listOf(BuildProblemSnapshot(label = "ignored")),
        )

        terminalFailureFields(snapshot, ProgressResponseOptions()).containsKey("problems") shouldBe false
    }

    @Test
    fun `terminalFailureFields includes failedTests only when requested`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = null,
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 1,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = listOf(":test"),
            recentEvents = emptyList(),
            totalEventCount = 2,
            failedTests = listOf(
                failedTestSnapshot(
                    className = "com.example.FooTest",
                    methodName = "bar",
                    failureMessage = "boom",
                ),
            ),
        )

        terminalFailureFields(snapshot, ProgressResponseOptions()).containsKey("failedTests") shouldBe false
        (((terminalFailureFields(snapshot, ProgressResponseOptions(includeTestDetails = true))["failedTests"] as List<*>)
            .single() as Map<*, *>)["failureMessage"]) shouldBe "boom"
    }

    @Test
    fun `terminalFailureFields includes failedTests for cancelled builds when requested`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_CANCELLED,
            currentOperation = null,
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 0,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = emptyList(),
            recentEvents = emptyList(),
            totalEventCount = 2,
            failedTests = listOf(
                failedTestSnapshot(
                    className = "com.example.FooTest",
                    methodName = "bar",
                    failureMessage = "boom",
                ),
            ),
        )

        terminalFailureFields(snapshot, ProgressResponseOptions(includeTestDetails = true))
            .containsKey("failedTests") shouldBe true
    }
}
