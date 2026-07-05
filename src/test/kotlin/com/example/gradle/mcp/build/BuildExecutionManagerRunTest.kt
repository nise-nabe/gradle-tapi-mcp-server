package com.example.gradle.mcp.build

import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.noopProjectConnection
import com.example.gradle.mcp.support.seedNoopConnection
import com.example.gradle.mcp.support.blockingProjectConnection
import com.example.gradle.mcp.support.failedTracker
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.succeededTracker
import com.example.gradle.mcp.support.testBuildRecord
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
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BuildExecutionManagerRunTest {
    private val manager = BuildExecutionManager(GradleConnectionManager())

    @Test
    fun `startBackground rejects second build for same project`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection()
        val concurrentManager = BuildExecutionManager(connectionManager)
        concurrentManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )

        val error = shouldThrow<McpException> {
            concurrentManager.startBackground(
                request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                notifier = null,
            )
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message.shouldContain("already running")
        error.message.shouldContain("gradle_get_build_status")
    }

    @Test
    fun `startBackground allows concurrent builds for different projects`(@TempDir otherProject: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection()
        connectionManager.seedNoopConnection(otherProject)
        val concurrentManager = BuildExecutionManager(connectionManager)
        concurrentManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )

        val result = concurrentManager.startBackground(
            request = BuildRunRequest(projectDirectory = otherProject, kind = BuildKind.TASKS, tasks = listOf("test")),
            notifier = null,
        )

        result["buildId"] shouldNotBe "running-build"
        result["status"] shouldBe "running"
    }

    @Test
    fun `startBackground rejects concurrent starts for same project`() {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        connectionManager.seedConnectionForTests(blockingProjectConnection(buildEntered, releaseBuild))
        val manager = BuildExecutionManager(connectionManager)
        val request = BuildRunRequest(
            projectDirectory = testProjectDirectory,
            kind = BuildKind.TASKS,
            tasks = listOf("test"),
        )

        val outcomes = runBlocking {
            listOf(
                async(Dispatchers.Default) { runCatching { manager.startBackground(request, notifier = null) } },
                async(Dispatchers.Default) { runCatching { manager.startBackground(request, notifier = null) } },
            ).awaitAll()
        }

        outcomes.count { it.isSuccess } shouldBe 1
        val failure = outcomes.single { it.isFailure }.exceptionOrNull().shouldNotBeNull()
        (failure as McpException).code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        failure.message.shouldContain("already running")

        buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        releaseBuild.countDown()
    }

    @Test
    fun `hasActiveBuild reports foreground build while running`() {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val connection = blockingProjectConnection(buildEntered, releaseBuild)
        connectionManager.seedConnectionForTests(connection)
        val manager = BuildExecutionManager(connectionManager)

        val buildThread = Thread {
            runBlocking {
                manager.runForeground(
                    request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                    notifier = null,
                )
            }
        }.apply { isDaemon = true }
        buildThread.start()

        buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        manager.hasActiveBuild().shouldBeTrue()

        releaseBuild.countDown()
        buildThread.join(5_000)
        manager.hasActiveBuild().shouldBeFalse()
    }

    @Test
    fun `startBackground rejects when max concurrent background builds reached`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection()
        val manager = BuildExecutionManager(connectionManager)
        val executor = manager.testExecutor()
        val maxBuilds = manager.maxConcurrentBackgroundBuilds()
        val tasksStarted = CountDownLatch(maxBuilds)
        val releaseTasks = CountDownLatch(1)
        repeat(maxBuilds) {
            executor.execute {
                tasksStarted.countDown()
                releaseTasks.await(5, TimeUnit.SECONDS)
            }
        }
        tasksStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val error = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("other")),
                notifier = null,
            )
        }
        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message.shouldContain("Maximum concurrent builds")

        releaseTasks.countDown()
    }

    @Test
    fun `runForeground rejects when another build is running for same project`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection()
        val manager = BuildExecutionManager(connectionManager)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )

        val error = shouldThrow<McpException> {
            runBlocking {
                manager.runForeground(
                    request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                    notifier = null,
                )
            }
        }

        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message.shouldContain("already running")
        manager.hasActiveBuild().shouldBeTrue()
    }

    @Test
    fun `status omits partial output by default for running builds`() {
        val streams = CapturingStreams().also { it.appendStdoutForTests("> Task :app:compileJava UP-TO-DATE\n") }
        manager.seedRunningBuildForTests(
            testBuildRecord(id = "running-build", streams = streams, tracker = runningTracker()),
        )

        val result = manager.status(
            "running-build",
            OutputLimitOptions(),
            ProgressResponseOptions(includeProgress = false),
        )

        result["status"] shouldBe "running"
        result["stdout"].shouldBeNull()
        result["stderr"].shouldBeNull()
    }

    @Test
    fun `status returns running build progress and partial output when includeOutput is true`() {
        val streams = CapturingStreams().also { it.appendStdoutForTests("> Task :app:compileJava UP-TO-DATE\n") }
        manager.seedRunningBuildForTests(
            testBuildRecord(id = "running-build", streams = streams, tracker = runningTracker()),
        )

        val resultWithoutProgress = manager.status(
            "running-build",
            OutputLimitOptions(includeOutput = true, maxOutputChars = 100),
            ProgressResponseOptions(includeProgress = false),
        )

        resultWithoutProgress["status"] shouldBe "running"
        resultWithoutProgress["progress"].shouldBeNull()
        (resultWithoutProgress["stdout"] as String) shouldContain "UP-TO-DATE"

        val resultWithProgress = manager.status(
            "running-build",
            OutputLimitOptions(includeOutput = true, maxOutputChars = 100),
            ProgressResponseOptions(includeProgress = true),
        )

        (resultWithProgress["progress"] as Map<*, *>)["status"] shouldBe "running"
    }

    @Test
    fun `status rejects projectDirectory hint that does not match in-memory build`(@TempDir projectA: File, @TempDir projectB: File) {
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "scoped-build",
                projectDirectory = projectA.absolutePath,
                tracker = runningTracker(),
            ),
        )

        val error = shouldThrow<McpException> {
            manager.status(
                buildId = "scoped-build",
                outputLimit = OutputLimitOptions(),
                progressOptions = ProgressResponseOptions(),
                projectDirectoryHint = projectB,
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe "Build scoped-build does not belong to project ${projectB.path}"
    }

    @Test
    fun `hasActiveBuild reports seeded running build`() {
        manager.seedRunningBuildForTests(testBuildRecord(id = "running-build", tracker = runningTracker()))
        manager.hasActiveBuild().shouldBeTrue()
    }

    @Test
    fun `completed build status includes outcome and build summary`() {
        val streams = CapturingStreams().also {
            it.appendStdoutForTests("BUILD SUCCESSFUL in 1s\n2 actionable tasks: 2 executed\n")
        }
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "completed-build",
                streams = streams,
                tracker = succeededTracker(),
            ) {
                finishedAt = Instant.now()
            },
        )

        val result = manager.status("completed-build", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        (result["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 1s"
        result["stdout"].shouldBeNull()
        result["stderr"].shouldBeNull()
        result["failedTaskCount"] shouldBe 0
        result["failedTasks"] shouldBe emptyList<String>()
        result["progress"].shouldBeNull()
    }

    @Test
    fun `completed build status includes stdout when includeOutput is true`() {
        val streams = CapturingStreams().also {
            it.appendStdoutForTests("BUILD SUCCESSFUL in 1s\n> Task :app:compileJava UP-TO-DATE\n")
        }
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "completed-build-with-output",
                streams = streams,
                tracker = succeededTracker(),
            ) {
                finishedAt = Instant.now()
            },
        )

        val result = manager.status(
            "completed-build-with-output",
            OutputLimitOptions(includeOutput = true),
            ProgressResponseOptions(),
        )

        (result["stdout"] as String) shouldContain "UP-TO-DATE"
    }

    @Test
    fun `completed failed build status includes failure fields without includeProgress`() {
        val streams = CapturingStreams().also {
            it.appendStdoutForTests(
                """
                > Task :examples:resilience4j-spring:test FAILED
                > com.linecorp.armeria.example.FooTest > testBar() FAILED
                BUILD FAILED in 2s
                1 actionable task: 1 executed
                """.trimIndent() + "\n",
            )
        }
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "failed-build",
                streams = streams,
                tracker = failedTracker(),
            ) {
                finishedAt = Instant.now()
                errorMessage = "Build failed"
            },
        )

        val result = manager.status("failed-build", OutputLimitOptions(), ProgressResponseOptions(includeProgress = false))

        result["status"] shouldBe "failed"
        result["outcome"] shouldBe "FAILED"
        result["failedTaskCount"] shouldBe 0
        result["failedTasks"] shouldBe emptyList<String>()
        result["progress"].shouldBeNull()
        (result["buildSummary"] as Map<*, *>)["failureSummary"] shouldBe listOf(
            ":examples:resilience4j-spring:test",
            "com.linecorp.armeria.example.FooTest > testBar()",
        )
    }

    @Test
    fun `startBackground returns immediately while build is still running`() {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        connectionManager.seedConnectionForTests(blockingProjectConnection(buildEntered, releaseBuild))
        val manager = BuildExecutionManager(connectionManager)

        val result = manager.startBackground(
            request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
            notifier = null,
        )

        result["status"] shouldBe "running"
        result["buildId"].shouldNotBeNull()
        buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        manager.hasActiveBuild().shouldBeTrue()

        releaseBuild.countDown()
        var attempts = 0
        while (manager.hasActiveBuild() && attempts < 50) {
            Thread.sleep(100)
            attempts++
        }
        manager.hasActiveBuild().shouldBeFalse()
    }

    @Test
    fun `runForeground detaches build when waiting thread is interrupted`() {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        connectionManager.seedConnectionForTests(blockingProjectConnection(buildEntered, releaseBuild))
        val manager = BuildExecutionManager(connectionManager)
        val resultRef = AtomicReference<Map<String, Any?>>()

        val waiterThread = Thread {
            resultRef.set(
                runBlocking {
                    manager.runForeground(
                        request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                        notifier = null,
                    )
                },
            )
        }.apply { isDaemon = true }
        waiterThread.start()

        buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        waiterThread.interrupt()
        waiterThread.join(5_000)

        val detached = resultRef.get()
        detached.shouldNotBeNull()
        detached["status"] shouldBe "running"
        detached["detached"] shouldBe true
        detached["buildId"].shouldNotBeNull()
        manager.hasActiveBuild().shouldBeTrue()

        releaseBuild.countDown()
        var attempts = 0
        while (manager.hasActiveBuild() && attempts < 50) {
            Thread.sleep(100)
            attempts++
        }
        manager.hasActiveBuild().shouldBeFalse()
    }

    @Test
    fun `runForeground returns detached response when coroutine is cancelled`() = runBlocking {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        connectionManager.seedConnectionForTests(blockingProjectConnection(buildEntered, releaseBuild))
        val manager = BuildExecutionManager(connectionManager)

        val deferred = async(Dispatchers.IO) {
            manager.runForeground(
                request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                notifier = null,
            )
        }

        buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        deferred.cancel()
        val detached = deferred.await()

        detached["status"] shouldBe "running"
        detached["detached"] shouldBe true
        detached["buildId"].shouldNotBeNull()
        manager.hasActiveBuild().shouldBeTrue()

        releaseBuild.countDown()
        var attempts = 0
        while (manager.hasActiveBuild() && attempts < 50) {
            Thread.sleep(100)
            attempts++
        }
        manager.hasActiveBuild().shouldBeFalse()
    }
}
