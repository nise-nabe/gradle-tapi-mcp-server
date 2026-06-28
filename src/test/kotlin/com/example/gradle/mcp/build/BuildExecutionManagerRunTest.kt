package com.example.gradle.mcp.build

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

class BuildExecutionManagerRunTest {
    private val manager = BuildExecutionManager(GradleConnectionManager())

    @Test
    fun `startBackground allows concurrent builds`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedNoopConnection()
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
            request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
            exchange = null,
            progressToken = null,
        )

        result["buildId"] shouldNotBe "running-build"
        result["status"] shouldBe "running"
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
            manager.runForeground(
                request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
                connection = connection,
                exchange = null,
                progressToken = null,
            )
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
        val executor = BuildExecutionManager::class.java
            .getDeclaredField("executor")
            .apply { isAccessible = true }
            .get(manager) as ExecutorService
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
                exchange = null,
                progressToken = null,
            )
        }
        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
        error.message.shouldNotBeNull() shouldContain "Maximum concurrent background builds"

        releaseTasks.countDown()
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
            request = BuildRunRequest(projectDirectory = testProjectDirectory, kind = BuildKind.TASKS, tasks = listOf("test")),
            connection = connection,
            exchange = null,
            progressToken = null,
        )

        result["status"] shouldBe "failed"
        manager.hasActiveBuild().shouldBeTrue()
    }

    @Test
    fun `status omits partial output by default for running builds`() {
        val streams = CapturingStreams()
        streams.appendStdoutForTests("> Task :app:compileJava UP-TO-DATE\n")
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
        val streams = CapturingStreams()
        streams.appendStdoutForTests("> Task :app:compileJava UP-TO-DATE\n")
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
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "scoped-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                testClasses = emptyList(),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
                projectDirectory = projectA.absolutePath,
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
        result["stdout"].shouldBeNull()
        result["stderr"].shouldBeNull()
        result["failedTaskCount"] shouldBe 0
        result["failedTasks"] shouldBe emptyList<String>()
        result["progress"].shouldBeNull()
    }

    @Test
    fun `completed build status includes stdout when includeOutput is true`() {
        val streams = CapturingStreams()
        streams.appendStdoutForTests("BUILD SUCCESSFUL in 1s\n> Task :app:compileJava UP-TO-DATE\n")

        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markSucceeded()
        val record = BuildRecord(
            id = "completed-build-with-output",
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
        ).also { it.finishedAt = Instant.now() }
        manager.seedRunningBuildForTests(record)

        val result = manager.status(
            "completed-build-with-output",
            OutputLimitOptions(includeOutput = true),
            ProgressResponseOptions(),
        )

        (result["stdout"] as String) shouldContain "UP-TO-DATE"
    }

    @Test
    fun `completed failed build status includes failure fields without includeProgress`() {
        val streams = CapturingStreams()
        streams.appendStdoutForTests(
            """
            > Task :examples:resilience4j-spring:test FAILED
            > com.linecorp.armeria.example.FooTest > testBar() FAILED
            BUILD FAILED in 2s
            1 actionable task: 1 executed
            """.trimIndent() + "\n",
        )

        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Build failed")
        val record = BuildRecord(
            id = "failed-build",
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = Instant.now(),
            progressTracker = tracker,
            streams = streams,
        ).also {
            it.finishedAt = Instant.now()
            it.errorMessage = "Build failed"
        }
        manager.seedRunningBuildForTests(record)

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
}
