package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProgressResponseOptionsTest {
    @Test
    fun `fromArgs defaults includeProgress to false`() {
        val options = ProgressResponseOptions.fromArgs(emptyMap())

        assertFalse(options.includeProgress)
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

        assertEquals(20, (response["completedTasks"] as List<*>).size)
        assertEquals(10, (response["recentEvents"] as List<*>).size)
        assertEquals("task-25", (response["completedTasks"] as List<*>).last())
        assertEquals("task-15", ((response["recentEvents"] as List<*>).last() as Map<*, *>)["displayName"])
    }
}
