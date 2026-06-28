package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.cache.lastMcpBuildInsight
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.support.testProjectDirectory
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class BuildExecutionManagerCancelTest {
    private val manager = BuildExecutionManager(GradleConnectionManager())

    @Test
    fun `cancelBuild requests cancellation for running build`() {
        val tokenSource = org.gradle.tooling.GradleConnector.newCancellationTokenSource()
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "cancellable-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
                cancellationTokenSource = tokenSource,
            ),
        )

        val result = manager.cancelBuild("cancellable-build")

        result["buildId"] shouldBe "cancellable-build"
        result["status"] shouldBe "running"
        tokenSource.token().isCancellationRequested.shouldBeTrue()
    }

    @Test
    fun `cancelBuild returns current status for terminal build`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markCancelled("already done")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "done-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
            ),
        )

        val result = manager.cancelBuild("done-build")

        result["status"] shouldBe "cancelled"
        result["message"] shouldBe "Build is not running."
    }

    @Test
    fun `cancelBuild throws for unknown build`() {
        val error = shouldThrow<McpException> {
            manager.cancelBuild("missing-build-id")
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldContain "Build not found"
    }

    @Test
    fun `resetBuildState cancels running builds and clears active state`() {
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
        status["status"] shouldBe "cancelled"
        status["error"] shouldBe "Preparing new Gradle connection"
        manager.hasActiveBuild().shouldBeFalse()
    }

    @Test
    fun `cancelBuild resolves build by id without projectDirectory hint`(@TempDir projectA: File, @TempDir projectB: File) {
        val connectionManager = GradleConnectionManager()
        val connection = noopProjectConnection()
        connectionManager.seedConnectionForTests(connection, projectDirectory = projectA)
        connectionManager.seedConnectionForTests(connection, projectDirectory = projectB)
        val scopedManager = BuildExecutionManager(connectionManager)
        val tokenSource = org.gradle.tooling.GradleConnector.newCancellationTokenSource()
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        scopedManager.seedRunningBuildForTests(
            BuildRecord(
                id = "multi-project-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
                cancellationTokenSource = tokenSource,
                projectDirectory = projectA.absolutePath,
            ),
        )

        val result = scopedManager.cancelBuild("multi-project-build")

        result["buildId"] shouldBe "multi-project-build"
        tokenSource.token().isCancellationRequested.shouldBeTrue()
    }

    @Test
    fun `onDisconnect cancels running builds`() {
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
        status["status"] shouldBe "cancelled"
        status["error"] shouldBe "Gradle connection closed"

        val notConnected = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                exchange = null,
                progressToken = null,
            )
        }
        notConnected.code shouldBe McpErrorCode.NOT_CONNECTED
        notConnected.message.shouldNotBeNull() shouldContain testProjectDirectory.path
    }

    @Test
    fun `onDisconnect replaces executor when last project is disconnected`(@TempDir project: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(project)
        val scopedManager = BuildExecutionManager(connectionManager)

        val executorBefore = BuildExecutionManager::class.java
            .getDeclaredField("executor")
            .apply { isAccessible = true }
            .get(scopedManager) as ExecutorService

        connectionManager.disconnect(project)
        scopedManager.onDisconnect(project)

        val executorAfter = BuildExecutionManager::class.java
            .getDeclaredField("executor")
            .apply { isAccessible = true }
            .get(scopedManager) as ExecutorService

        executorBefore shouldNotBe executorAfter
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
        val taskFinished = CountDownLatch(1)
        executor.execute {
            try {
                taskEntered.countDown()
                releaseBlock.await()
            } finally {
                taskFinished.countDown()
            }
        }
        taskEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val shutdownThread = Thread { manager.shutdown() }.apply { isDaemon = true }
        shutdownThread.start()

        taskFinished.await(500, TimeUnit.MILLISECONDS).shouldBeTrue()
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

        val snapshot = manager.lastCompletedBuildSnapshot(projectDir)
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
    fun `onDisconnect clears last completed build snapshot for disconnected project`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(projectA)
        val scopedManager = BuildExecutionManager(connectionManager)
        scopedManager.seedLastCompletedBuildForTests(
            CompletedBuildSnapshot(
                buildId = "a1",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                finishedAt = Instant.now(),
                outcome = "SUCCESS",
                stdout = "",
                projectDirectory = projectA.absolutePath,
            ),
        )
        scopedManager.seedLastCompletedBuildForTests(
            CompletedBuildSnapshot(
                buildId = "b1",
                kind = BuildKind.TASKS,
                tasks = listOf("check"),
                testClasses = emptyList(),
                finishedAt = Instant.now(),
                outcome = "SUCCESS",
                stdout = "",
                projectDirectory = projectB.absolutePath,
            ),
        )

        connectionManager.disconnect(projectA)
        scopedManager.onDisconnect(projectA)

        scopedManager.lastCompletedBuildSnapshot(projectA).shouldBeNull()
        scopedManager.lastCompletedBuildSnapshot(projectB).shouldNotBeNull()
    }

    @Test
    fun `onDisconnect without projectDirectory clears all last completed build snapshots`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val manager = BuildExecutionManager(GradleConnectionManager())
        manager.seedLastCompletedBuildForTests(
            CompletedBuildSnapshot(
                buildId = "a1",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                finishedAt = Instant.now(),
                outcome = "SUCCESS",
                stdout = "",
                projectDirectory = projectA.absolutePath,
            ),
        )
        manager.seedLastCompletedBuildForTests(
            CompletedBuildSnapshot(
                buildId = "b1",
                kind = BuildKind.TASKS,
                tasks = listOf("check"),
                testClasses = emptyList(),
                finishedAt = Instant.now(),
                outcome = "SUCCESS",
                stdout = "",
                projectDirectory = projectB.absolutePath,
            ),
        )

        manager.onDisconnect()

        manager.lastCompletedBuildSnapshot(projectA).shouldBeNull()
        manager.lastCompletedBuildSnapshot(projectB).shouldBeNull()
    }
}
