package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.cache.lastMcpBuildInsight
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
        val result = manager.status("missing-build-id", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "not_found"
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

        val error = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
                exchange = null,
                progressToken = null,
            )
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message shouldBe
            "Another build is already running (buildId=running-build). Call gradle_get_build_status first."
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
        val error = shouldThrow<McpException> {
            manager.runForeground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
                connection = unusedConnection,
                exchange = null,
                progressToken = null,
            )
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message shouldBe
            "Another build is already running (buildId=running-build). Call gradle_get_build_status first."
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

        val resultWithoutProgress = manager.status(
            "running-build",
            OutputLimitOptions(maxOutputChars = 100),
            ProgressResponseOptions(includeProgress = false),
        )

        resultWithoutProgress["status"] shouldBe "running"
        resultWithoutProgress["progress"].shouldBeNull()

        val resultWithProgress = manager.status(
            "running-build",
            OutputLimitOptions(maxOutputChars = 100),
            ProgressResponseOptions(includeProgress = true),
        )

        (resultWithProgress["progress"] as Map<*, *>)["status"] shouldBe "running"
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

        manager.hasActiveBuild().shouldBeTrue()
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

        val status = manager.status("running-build", OutputLimitOptions(), ProgressResponseOptions())
        status["status"] shouldBe "failed"
        status["error"] shouldBe "Preparing new Gradle connection"
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

        val status = manager.status("running-build", OutputLimitOptions(), ProgressResponseOptions())
        status["status"] shouldBe "failed"
        status["error"] shouldBe "Gradle connection closed"

        val slotError = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
                exchange = null,
                progressToken = null,
            )
        }
        slotError.code shouldBe McpErrorCode.NOT_CONNECTED
        slotError.message shouldBe
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR."
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

        val error = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("other")),
                exchange = null,
                progressToken = null,
            )
        }
        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message shouldBe
            "Another build is already running (buildId=new-build). Call gradle_get_build_status first."
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
        taskEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val shutdownThread = Thread { manager.shutdown() }.apply { isDaemon = true }
        shutdownThread.start()

        slotReleased.await(500, TimeUnit.MILLISECONDS).shouldBeTrue()
        shutdownThread.join(10_000)
    }

    @Test
    fun `resetBuildState snapshot keeps project directory from build start`(@TempDir projectDir: File) {
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
                projectDirectory = projectDir.absolutePath,
            ),
        )

        manager.resetBuildState("Gradle connection closed")

        val snapshot = manager.lastCompletedBuildSnapshot()
        snapshot.shouldNotBeNull()
        snapshot.projectDirectory shouldBe projectDir.absolutePath
    }

    @Test
    fun `lastMcpBuildInsight omits snapshot from a different project`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        manager.seedLastCompletedBuildForTests(
            CompletedBuildSnapshot(
                buildId = "b1",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                finishedAt = Instant.now(),
                outcome = "SUCCESS",
                stdout = "BUILD SUCCESSFUL in 1s\n3 actionable tasks: 2 executed, 1 from cache\n",
                projectDirectory = projectA.absolutePath,
            ),
        )

        manager.lastMcpBuildInsight(projectB).shouldBeNull()

        val insight = manager.lastMcpBuildInsight(projectA)
        insight.shouldNotBeNull()
        insight.buildId shouldBe "b1"
        insight.taskStats?.executed shouldBe 2
    }

    @Test
    fun `lastMcpBuildInsight exposes test classes separately from tasks`(@TempDir projectDir: File) {
        manager.seedLastCompletedBuildForTests(
            CompletedBuildSnapshot(
                buildId = "test-run",
                kind = BuildKind.TESTS,
                tasks = emptyList(),
                testClasses = listOf("com.example.FooTest"),
                finishedAt = Instant.now(),
                outcome = "SUCCESS",
                stdout = "BUILD SUCCESSFUL in 1s\n",
                projectDirectory = projectDir.absolutePath,
            ),
        )

        val insight = manager.lastMcpBuildInsight(projectDir)
        insight.shouldNotBeNull()
        insight.tasks shouldBe emptyList<String>()
        insight.testClasses shouldBe listOf("com.example.FooTest")
    }

    @Test
    fun `completed build status includes outcome and build summary`() {
        val streams = CapturingStreams()
        streams.appendStdoutForTests("BUILD SUCCESSFUL in 1s\n2 actionable tasks: 2 executed\n")

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

        val result = manager.status("completed-build", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        (result["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 1s"
    }
}
