package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.cache.lastMcpBuildInsight
import com.example.gradle.mcp.connection.GradleConnectionManager
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
    fun `startBackground allows concurrent builds`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
        )
        val concurrentManager = BuildExecutionManager(connectionManager)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        concurrentManager.seedRunningBuildForTests(
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

        val result = concurrentManager.startBackground(
            request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
            exchange = null,
            progressToken = null,
        )

        result["buildId"] shouldNotBe "running-build"
        result["status"] shouldBe "running"
    }

    @Test
    fun `runForeground does not reject when another build is running`() {
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

        val connection = Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, method, _ ->
                if (method.name == "newBuild") {
                    throw UnsupportedOperationException("simulated build failure")
                }
                null
            },
        ) as ProjectConnection

        val result = manager.runForeground(
            request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
            connection = connection,
            exchange = null,
            progressToken = null,
        )

        result["status"] shouldBe "failed"
        manager.hasActiveBuild().shouldBeTrue()
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
    fun `resetBuildState marks running builds failed and clears active state`() {
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
        manager.hasActiveBuild().shouldBeFalse()
    }

    @Test
    fun `onDisconnect marks running builds failed`() {
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

        val notConnected = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(kind = BuildKind.TASKS, tasks = listOf("test")),
                exchange = null,
                progressToken = null,
            )
        }
        notConnected.code shouldBe McpErrorCode.NOT_CONNECTED
        notConnected.message shouldBe
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR."
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
