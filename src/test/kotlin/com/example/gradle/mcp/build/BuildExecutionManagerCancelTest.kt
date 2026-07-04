package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.lastMcpBuildInsight
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.blockingProjectConnection
import com.example.gradle.mcp.support.cancelledTracker
import com.example.gradle.mcp.support.failedTracker
import com.example.gradle.mcp.support.interruptedOnRunProjectConnection
import com.example.gradle.mcp.support.noopProjectConnection
import com.example.gradle.mcp.support.seedNoopConnection
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testCompletedSnapshot
import com.example.gradle.mcp.support.testExecutor
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.gradle.tooling.GradleConnector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BuildExecutionManagerCancelTest {
    private val manager = BuildExecutionManager(GradleConnectionManager())

    @Test
    fun `cancelBuild requests cancellation for running build`() {
        val tokenSource = GradleConnector.newCancellationTokenSource()
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "cancellable-build",
                tracker = runningTracker(),
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
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "done-build",
                tracker = cancelledTracker(),
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
        manager.seedRunningBuildForTests(testBuildRecord(id = "running-build", tracker = runningTracker()))

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
        val tokenSource = GradleConnector.newCancellationTokenSource()
        scopedManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "multi-project-build",
                tracker = runningTracker(),
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
        manager.seedRunningBuildForTests(testBuildRecord(id = "running-build", tracker = runningTracker()))

        manager.onDisconnect()

        val status = manager.status("running-build", OutputLimitOptions(), ProgressResponseOptions())
        status["status"] shouldBe "cancelled"
        status["error"] shouldBe "Gradle connection closed"

        val notConnected = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                notifier = null,
            )
        }
        notConnected.code shouldBe McpErrorCode.NOT_CONNECTED
        notConnected.message.shouldContain(testProjectDirectory.path)
    }

    @Test
    fun `onDisconnect replaces executor when last project is disconnected`(@TempDir project: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection(project)
        val scopedManager = BuildExecutionManager(connectionManager)

        val executorBefore = scopedManager.testExecutor()

        connectionManager.disconnect(project)
        scopedManager.onDisconnect(project)

        scopedManager.testExecutor() shouldNotBe executorBefore
    }

    @Test
    fun `InterruptedException during build is recorded as cancelled`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(interruptedOnRunProjectConnection())
        val manager = BuildExecutionManager(connectionManager)

        val result = runBlocking {
            manager.runForeground(
                request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                notifier = null,
            )
        }

        result["status"] shouldBe "cancelled"
        (result["error"] as String) shouldContain "interrupted"
        manager.hasActiveBuild().shouldBeFalse()
    }

    @Test
    fun `shutdown releases lifecycle lock before awaiting executor termination`() {
        val manager = BuildExecutionManager(GradleConnectionManager())
        manager.seedRunningBuildForTests(testBuildRecord(id = "running-build", tracker = runningTracker()))

        val executor = manager.testExecutor()
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

        val shutdownStarted = CountDownLatch(1)
        val shutdownThread = Thread {
            shutdownStarted.countDown()
            manager.shutdown()
        }.apply { isDaemon = true }
        shutdownThread.start()

        shutdownStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        releaseBlock.countDown()
        taskFinished.await(5, TimeUnit.SECONDS).shouldBeTrue()
        shutdownThread.join(10_000)
    }

    @Test
    fun `resetBuildState snapshot keeps project directory from build start`(@TempDir projectDir: File) {
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
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
            testCompletedSnapshot(
                buildId = "b1",
                projectDirectory = projectA.absolutePath,
                stdout = "BUILD SUCCESSFUL in 1s\n3 actionable tasks: 2 executed, 1 from cache\n",
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
            testCompletedSnapshot(
                buildId = "test-run",
                projectDirectory = projectDir.absolutePath,
                kind = BuildKind.TESTS,
                tasks = emptyList(),
                testClasses = listOf("com.example.FooTest"),
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
            testCompletedSnapshot(buildId = "a1", projectDirectory = projectA.absolutePath),
        )
        scopedManager.seedLastCompletedBuildForTests(
            testCompletedSnapshot(
                buildId = "b1",
                projectDirectory = projectB.absolutePath,
                tasks = listOf("check"),
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
            testCompletedSnapshot(buildId = "a1", projectDirectory = projectA.absolutePath),
        )
        manager.seedLastCompletedBuildForTests(
            testCompletedSnapshot(
                buildId = "b1",
                projectDirectory = projectB.absolutePath,
                tasks = listOf("check"),
            ),
        )

        manager.onDisconnect()

        manager.lastCompletedBuildSnapshot(projectA).shouldBeNull()
        manager.lastCompletedBuildSnapshot(projectB).shouldBeNull()
    }
}
