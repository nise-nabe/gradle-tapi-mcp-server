package com.example.mcp

import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class BuildExecutionManagerTest {
    private val manager = BuildExecutionManager(GradleConnectionManager())

    @Test
    fun `status returns not_found for unknown build`() {
        val result = manager.status("missing-build-id", OutputLimitOptions())

        assertEquals("not_found", result["status"])
    }

    @Test
    fun `startBackground rejects when another build is running`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "running-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
            ),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            manager.startBackground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
                exchange = null,
                progressToken = null,
            )
        }

        assertEquals(
            "Another build is already running (buildId=running-build). Call gradle_get_build_status first.",
            error.message,
        )
    }

    @Test
    fun `runForeground rejects when another build is running`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "running-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
            ),
        )

        val unusedConnection = Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, _, _ -> null },
        ) as ProjectConnection
        val error = assertThrows(IllegalStateException::class.java) {
            manager.runForeground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
                connection = unusedConnection,
                exchange = null,
                progressToken = null,
            )
        }

        assertEquals(
            "Another build is already running (buildId=running-build). Call gradle_get_build_status first.",
            error.message,
        )
    }

    @Test
    fun `status returns running build progress and partial output`() {
        val streams = CapturingStreams()
        streams.stdoutText()
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "running-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = streams,
            ),
        )

        val result = manager.status("running-build", OutputLimitOptions(maxOutputChars = 100))

        assertEquals("running", result["status"])
        assertEquals("running", (result["progress"] as Map<*, *>)["status"])
    }

    @Test
    fun `hasActiveBuild reports seeded running build`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "running-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
            ),
        )

        assertTrue(manager.hasActiveBuild())
    }

    @Test
    fun `resetBuildState releases build slot and marks running build failed`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "running-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
            ),
        )

        manager.resetBuildState("Preparing new Gradle connection")

        val status = manager.status("running-build", OutputLimitOptions())
        assertEquals("failed", status["status"])
        assertEquals("Preparing new Gradle connection", status["error"])
    }

    @Test
    fun `onDisconnect releases build slot and marks running build failed`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "running-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
            ),
        )

        manager.onDisconnect()

        val status = manager.status("running-build", OutputLimitOptions())
        assertEquals("failed", status["status"])
        assertEquals("Gradle connection closed", status["error"])

        val slotError = assertThrows(IllegalStateException::class.java) {
            manager.startBackground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
                exchange = null,
                progressToken = null,
            )
        }
        assertEquals(
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR.",
            slotError.message,
        )
    }

    @Test
    fun `stale runner finally does not release a new build slot`() {
        val staleTracker = BuildProgressTracker()
        staleTracker.markStarting("Gradle tasks: build")
        val staleRecord = BuildRecord(
            id = "stale-build",
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = Instant.now(),
            progressTracker = staleTracker,
            streams = CapturingStreams(),
        )
        manager.seedRunningBuildForTests(staleRecord)

        manager.onDisconnect()

        val newTracker = BuildProgressTracker()
        newTracker.markStarting("Gradle tasks: test")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "new-build",
                kind = BuildKind.TASKS,
                tasks = listOf("test"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = newTracker,
                streams = CapturingStreams(),
            ),
        )

        manager.releaseBuildSlotIfActive(staleRecord)

        val error = assertThrows(IllegalStateException::class.java) {
            manager.startBackground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("other")),
                exchange = null,
                progressToken = null,
            )
        }
        assertEquals(
            "Another build is already running (buildId=new-build). Call gradle_get_build_status first.",
            error.message,
        )
    }

    @Test
    fun `shutdown releases lifecycle lock before awaiting executor termination`() {
        val manager = BuildExecutionManager(GradleConnectionManager())
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        val record = BuildRecord(
            id = "running-build",
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = CapturingStreams(),
        )
        manager.seedRunningBuildForTests(record)

        val executor = BuildExecutionManager::class.java
            .getDeclaredField("executor")
            .apply { isAccessible = true }
            .get(manager) as ExecutorService

        val taskEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val slotReleased = CountDownLatch(1)
        executor.execute {
            try {
                taskEntered.countDown()
                releaseBlock.await()
            } finally {
                manager.releaseBuildSlotIfActive(record)
                slotReleased.countDown()
            }
        }
        assertTrue(taskEntered.await(5, TimeUnit.SECONDS), "build executor task should start")

        val shutdownThread = Thread { manager.shutdown() }.apply { isDaemon = true }
        shutdownThread.start()

        assertTrue(
            slotReleased.await(500, TimeUnit.MILLISECONDS),
            "releaseBuildSlotIfActive should not block until shutdown finishes awaiting termination",
        )
        shutdownThread.join(10_000)
    }
}
