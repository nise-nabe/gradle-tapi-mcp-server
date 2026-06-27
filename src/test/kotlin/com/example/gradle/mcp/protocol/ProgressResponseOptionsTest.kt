package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.ProgressEventSnapshot
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProgressResponseOptionsTest {
    @Test
    fun `fromArgs defaults includeProgress to false`() {
        val options = ProgressResponseOptions.fromArgs(emptyMap())

        options.includeProgress.shouldBeFalse()
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

        val response = snapshot.toResponseMap()

        (response["completedTasks"] as List<*>).size shouldBe 20
        (response["recentEvents"] as List<*>).size shouldBe 10
        (response["completedTasks"] as List<*>).last() shouldBe "task-25"
        ((response["recentEvents"] as List<*>).last() as Map<*, *>)["displayName"] shouldBe "task-15"
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

        val fields = terminalFailureFields(snapshot)

        fields["failedTaskCount"] shouldBe 1
        fields["failedTasks"] shouldBe listOf(":app:compileJava")
        (fields["problems"] as List<*>).single().let { problem ->
            (problem as Map<*, *>)["label"] shouldBe "Compilation failed"
        }
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

        terminalFailureFields(snapshot).containsKey("problems") shouldBe false
    }
}
