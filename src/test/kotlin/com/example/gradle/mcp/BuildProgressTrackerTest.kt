package com.example.gradle.mcp

import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestStartEvent
import org.gradle.tooling.events.test.TestSuccessResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class BuildProgressTrackerTest {
    @Test
    fun `markFailed does not overwrite succeeded status`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markSucceeded()

        tracker.markFailed("late failure")

        assertEquals(BuildProgressTracker.STATUS_SUCCEEDED, tracker.snapshot().status)
    }

    @Test
    fun `markSucceeded does not overwrite failed status`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Gradle connection closed")

        tracker.markSucceeded()

        assertEquals(BuildProgressTracker.STATUS_FAILED, tracker.snapshot().status)
    }

    @Test
    fun `terminal transitions do not emit duplicate update callbacks`() {
        var notifyCount = 0
        val tracker = BuildProgressTracker(onUpdate = { notifyCount++ })
        tracker.markStarting("Gradle tasks: build")
        val afterStart = notifyCount
        tracker.markSucceeded()
        val afterSuccess = notifyCount

        tracker.markFailed("late failure")
        assertEquals(afterSuccess, notifyCount)
        assertTrue(afterStart > 0)
        assertTrue(afterSuccess > afterStart)

        tracker.markSucceeded()
        assertEquals(afterSuccess, notifyCount)
    }

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
    fun `tracks test start and finish events`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val testName = "com.example.DemoTest"

        val testStart = Proxy.newProxyInstance(
            TestStartEvent::class.java.classLoader,
            arrayOf(TestStartEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> testName
                    "getEventTime" -> 0L
                    else -> null
                }
            },
        ) as TestStartEvent
        listener.statusChanged(testStart)

        var snapshot = tracker.snapshot()
        assertEquals(1, snapshot.runningTaskCount)
        assertEquals(testName, snapshot.runningTasks.single())

        val testFinish = Proxy.newProxyInstance(
            TestFinishEvent::class.java.classLoader,
            arrayOf(TestFinishEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> testName
                    "getEventTime" -> 1L
                    "getResult" -> Proxy.newProxyInstance(
                        TestSuccessResult::class.java.classLoader,
                        arrayOf(TestSuccessResult::class.java),
                        InvocationHandler { _, _, _ -> null },
                    )
                    else -> null
                }
            },
        ) as TestFinishEvent
        listener.statusChanged(testFinish)

        snapshot = tracker.snapshot()
        assertEquals(0, snapshot.runningTaskCount)
        assertEquals(1, snapshot.completedTaskCount)
        assertEquals("TEST_SUCCESS", snapshot.recentEvents.last().eventType)
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
