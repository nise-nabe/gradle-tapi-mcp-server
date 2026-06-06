package com.example.mcp

import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.time.Instant

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
    fun `completed build status includes outcome and build summary`() {
        val streams = CapturingStreams()
        val captureField = CapturingStreams::class.java.getDeclaredField("stdoutCapture")
        captureField.isAccessible = true
        val capture = captureField.get(streams) as TailCapturingStream
        val stdout = "BUILD SUCCESSFUL in 1s\n2 actionable tasks: 2 executed\n"
        val bytes = stdout.toByteArray()
        capture.append(bytes, 0, bytes.size)

        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markSucceeded()
        val record = BuildRecord(
            id = "completed-build",
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
        ).also { it.finishedAt = Instant.now() }
        manager.seedRunningBuildForTests(record)

        val result = manager.status("completed-build", OutputLimitOptions())

        assertEquals("succeeded", result["status"])
        assertEquals("SUCCESS", result["outcome"])
        assertEquals(
            "BUILD SUCCESSFUL in 1s",
            (result["buildSummary"] as Map<*, *>)["resultLine"],
        )
    }
}
