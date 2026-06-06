package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildProgressTrackerTest {
    @Test
    fun `tracks lifecycle status transitions`() {
        val tracker = BuildProgressTracker()

        tracker.markStarting("Gradle tasks: build")
        assertEquals(BuildProgressTracker.STATUS_RUNNING, tracker.snapshot().status)

        tracker.markSucceeded()
        val snapshot = tracker.snapshot()
        assertEquals(BuildProgressTracker.STATUS_SUCCEEDED, snapshot.status)
        assertEquals(0, snapshot.runningTaskCount)
    }

    @Test
    fun `notifies only when new progress events arrive`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("build")

        assertTrue(tracker.shouldNotifyProgress())
        assertEquals(false, tracker.shouldNotifyProgress())

        tracker.markSucceeded()
        assertTrue(tracker.shouldNotifyProgress())
    }

    @Test
    fun `keeps only the most recent events`() {
        val tracker = BuildProgressTracker()
        repeat(40) { index ->
            tracker.markStarting("step-$index")
        }

        val snapshot = tracker.snapshot()
        assertTrue(snapshot.recentEvents.size <= 30)
        assertEquals(40, snapshot.totalEventCount)
    }
}
