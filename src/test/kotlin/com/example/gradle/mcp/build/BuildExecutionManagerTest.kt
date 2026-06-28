package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.build.persistence.GradleBuildResult
import com.example.gradle.mcp.build.persistence.McpBuildRecordPaths
import com.example.gradle.mcp.build.persistence.McpBuildResult
import com.example.gradle.mcp.cache.CompletedBuildSnapshot
import com.example.gradle.mcp.protocol.mcpObjectMapper
import com.example.gradle.mcp.cache.lastMcpBuildInsight
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.support.testProjectDirectory
import com.example.gradle.mcp.support.withWorkspaceDirectory
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
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
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
    fun `status loads persisted build from project gradle directory`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val persistedManager = BuildExecutionManager(connectionManager, store)
        val buildId = "disk-only-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                    buildSummary = mapOf("resultLine" to "BUILD SUCCESSFUL in 1s"),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val result = persistedManager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        result["buildId"] shouldBe buildId
    }

    @Test
    fun `status loads persisted build from workspace env when disconnected`(@TempDir projectDir: File) {
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(GradleConnectionManager(), store)
        val buildId = "workspace-disk-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        withWorkspaceDirectory(projectDir) {
            val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

            result["status"] shouldBe "succeeded"
            result["buildId"] shouldBe buildId
        }
    }

    @Test
    fun `status rejects path traversal buildId`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val leakedDir = File(projectDir, ".gradle/leaked-build")
        leakedDir.mkdirs()
        File(leakedDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = "leaked",
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        val manager = BuildExecutionManager(connectionManager, BuildRecordStore())

        val result = manager.status("../leaked-build", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "not_found"
    }

    @Test
    fun `status prefers disk gradle succeeded over memory failed after disconnect`(@TempDir projectDir: File) {
        val buildId = "disconnect-merge-build"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Gradle connection closed")
        val streams = CapturingStreams()
        streams.appendStdoutForTests(
            "partial\nBUILD SUCCESSFUL in 2s\n2 actionable tasks: 2 executed\n",
        )
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = buildId,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = tracker,
                streams = streams,
                projectDirectory = projectDir.absolutePath,
            ).also {
                it.finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                it.errorMessage = "Gradle connection closed"
            },
        )
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.STDOUT_LOG).writeText(
            "partial\n",
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "succeeded",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "failed",
                    outcome = "FAILED",
                    error = "Gradle connection closed",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        result["statusSource"] shouldBe "disk"
        result.containsKey("error") shouldBe false
        result.containsKey("failedTaskCount") shouldBe false
        result.containsKey("failedTasks") shouldBe false
        (result["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 2s"
        (result["buildSummary"] as Map<*, *>)["taskSummaryLine"] shouldBe "2 actionable tasks: 2 executed"

        val withOutput = manager.status(
            buildId,
            OutputLimitOptions(includeOutput = true),
            ProgressResponseOptions(),
        )
        withOutput["stdoutTotalChars"] shouldBe streams.stdoutSnapshot().totalChars
        (withOutput["stdout"] as String) shouldContain "BUILD SUCCESSFUL in 2s"
    }

    @Test
    fun `status prefers disk gradle running over memory failed after disconnect`(@TempDir projectDir: File) {
        val buildId = "disconnect-still-running"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Gradle connection closed")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = buildId,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = tracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ).also {
                it.finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                it.errorMessage = "Gradle connection closed"
            },
        )
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "failed",
                    outcome = "FAILED",
                    error = "Gradle connection closed",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "disk"
        result["liveProgress"] shouldBe false
        result.containsKey("error") shouldBe false
    }

    @Test
    fun `status loads disk build from explicit projectDirectory when connected elsewhere`(@TempDir projectDirs: File) {
        val projectA = projectDirs.resolve("project-a").also { it.mkdirs() }
        val projectB = projectDirs.resolve("project-b").also { it.mkdirs() }
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectB,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val buildId = "cross-project-build"
        val recordDir = store.recordDirectory(projectA, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectA.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val withoutHint = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())
        withoutHint["status"] shouldBe "not_found"

        val withHint = manager.status(
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(),
            projectDirectoryHint = projectA,
        )
        withHint["status"] shouldBe "succeeded"
        withHint["buildId"] shouldBe buildId
    }

    @Test
    fun `status omits stdout on disk running poll even when includeOutput is true`(@TempDir projectDir: File) {
        val buildId = "disk-running-no-stdout"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val result = manager.status(
            buildId,
            OutputLimitOptions(includeOutput = true),
            ProgressResponseOptions(),
        )

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "disk"
        result.containsKey("stdout") shouldBe false
        result.containsKey("stderr") shouldBe false
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
        connectionManager.seedConnectionForTests(
            Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
        )
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
    fun `cancelBuild requests cancellation for running build`() {
        val tokenSource = org.gradle.tooling.GradleConnector.newCancellationTokenSource()
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "cancellable-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
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
    fun `hasActiveBuild reports seeded running build`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "running-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.now(),
                progressTracker = tracker,
                streams = CapturingStreams(),
            ),
        )

        manager.hasActiveBuild().shouldBeTrue()
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
        val connection = Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, _, _ -> null },
        ) as ProjectConnection
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
        val connection = Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, _, _ -> null },
        ) as ProjectConnection
        connectionManager.seedConnectionForTests(connection, projectDirectory = project)
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

    @Test
    fun `status skips disk merge while in-memory build is still running`(@TempDir projectDir: File) {
        val buildId = "active-running-build"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = buildId,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = tracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ),
        )
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        val staleFinishedAt = Instant.now().minusSeconds(120).toString()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = staleFinishedAt,
                    status = "failed",
                    outcome = "FAILED",
                    error = "Gradle connection closed",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """{"ts":"2026-06-14T10:00:30Z","type":"TASK_START","displayName":":app:build"}""" + "\n",
            StandardCharsets.UTF_8,
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "memory"
        result.containsKey("error") shouldBe false
    }

    @Test
    fun `listBuilds returns memory and disk builds sorted by recency`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)

        val diskOnlyId = "disk-only-build"
        val recordDir = store.recordDirectory(projectDir, diskOnlyId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = diskOnlyId,
                    kind = "tasks",
                    tasks = listOf("check"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T08:00:00Z",
                    finishedAt = "2026-06-14T08:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val memoryTracker = BuildProgressTracker()
        memoryTracker.markStarting("Gradle tasks: build")
        memoryTracker.markSucceeded()
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "memory-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = memoryTracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ).also { it.finishedAt = Instant.parse("2026-06-14T10:01:00Z") },
        )

        val result = manager.listBuilds(projectDir, limit = 10)
        val builds = result["builds"] as List<*>

        result["projectDirectory"] shouldBe projectDir.absolutePath
        result["totalAvailable"] shouldBe 2
        result["truncated"] shouldBe false
        builds.map { (it as Map<*, *>)["buildId"] } shouldBe listOf("memory-build", diskOnlyId)
        (builds[0] as Map<*, *>)["recordSource"] shouldBe "memory"
        (builds[1] as Map<*, *>)["recordSource"] shouldBe "disk"
    }

    @Test
    fun `listBuilds prefers memory record over disk for same buildId`(@TempDir projectDir: File) {
        val buildId = "shared-build"
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("still running in memory view")
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = buildId,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = tracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ),
        )
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val result = manager.listBuilds(projectDir, limit = 10)
        val builds = result["builds"] as List<Map<*, *>>

        builds.single()["status"] shouldBe "failed"
        builds.single()["recordSource"] shouldBe "memory"
    }

    @Test
    fun `listBuilds applies limit and truncated flag`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        repeat(3) { index ->
            val buildId = "build-$index"
            val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
            recordDir.mkdirs()
            val finishedAt = "2026-06-14T10:0${index}:00Z"
            File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
                mcpObjectMapper().writeValueAsString(
                    McpBuildResult(
                        buildId = buildId,
                        kind = "tasks",
                        tasks = listOf("build"),
                        testClasses = emptyList(),
                        projectDirectory = projectDir.absolutePath,
                        startedAt = finishedAt,
                        finishedAt = finishedAt,
                        status = "succeeded",
                        outcome = "SUCCESS",
                    ),
                ),
                StandardCharsets.UTF_8,
            )
        }

        val result = manager.listBuilds(projectDir, limit = 2)
        val builds = result["builds"] as List<*>

        builds.size shouldBe 2
        result["totalAvailable"] shouldBe 3
        result["truncated"] shouldBe true
    }

    @Test
    fun `listBuilds ranks disk builds by persisted timestamps not file mtime`(@TempDir projectDir: File) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectDir,
        )
        val store = BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)

        val olderMemoryTracker = BuildProgressTracker()
        olderMemoryTracker.markStarting("Gradle tasks: build")
        olderMemoryTracker.markSucceeded()
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "memory-build",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T08:00:00Z"),
                progressTracker = olderMemoryTracker,
                streams = CapturingStreams(),
                projectDirectory = projectDir.absolutePath,
            ).also { it.finishedAt = Instant.parse("2026-06-14T08:01:00Z") },
        )

        val newerDiskId = "newer-disk-build"
        val newerRecordDir = store.recordDirectory(projectDir, newerDiskId).shouldNotBeNull()
        newerRecordDir.mkdirs()
        val newerResultFile = File(newerRecordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
        newerResultFile.writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = newerDiskId,
                    kind = "tasks",
                    tasks = listOf("check"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T12:00:00Z",
                    finishedAt = "2026-06-14T12:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        newerResultFile.setLastModified(Instant.parse("2026-06-14T01:00:00Z").toEpochMilli())

        val staleDiskId = "stale-disk-build"
        val staleRecordDir = store.recordDirectory(projectDir, staleDiskId).shouldNotBeNull()
        staleRecordDir.mkdirs()
        val staleResultFile = File(staleRecordDir, McpBuildRecordPaths.MCP_RESULT_FILE)
        staleResultFile.writeText(
            mcpObjectMapper().writeValueAsString(
                McpBuildResult(
                    buildId = staleDiskId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T06:00:00Z",
                    finishedAt = "2026-06-14T06:01:00Z",
                    status = "succeeded",
                    outcome = "SUCCESS",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        staleResultFile.setLastModified(Instant.parse("2026-06-14T23:00:00Z").toEpochMilli())

        val result = manager.listBuilds(projectDir, limit = 1)
        val builds = result["builds"] as List<*>

        builds.map { (it as Map<*, *>)["buildId"] } shouldBe listOf(newerDiskId)
        (builds.single() as Map<*, *>)["recordSource"] shouldBe "disk"
    }

    @Test
    fun `listBuilds without projectDirectory returns all in-memory builds across projects`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectA,
        )
        val manager = BuildExecutionManager(connectionManager)

        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "build-a",
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                progressTracker = BuildProgressTracker().also { it.markStarting("Gradle tasks: build") },
                streams = CapturingStreams(),
                projectDirectory = projectA.absolutePath,
            ),
        )
        manager.seedRunningBuildForTests(
            BuildRecord(
                id = "build-b",
                kind = BuildKind.TASKS,
                tasks = listOf("check"),
                startedAt = Instant.parse("2026-06-14T09:00:00Z"),
                progressTracker = BuildProgressTracker().also { it.markStarting("Gradle tasks: check") },
                streams = CapturingStreams(),
                projectDirectory = projectB.absolutePath,
            ),
        )

        val result = manager.listBuilds(projectDirectoryHint = null, limit = 10)
        val builds = result["builds"] as List<*>

        result["projectDirectory"] shouldBe projectA.absolutePath
        result["totalAvailable"] shouldBe 2
        builds.map { (it as Map<*, *>)["buildId"] } shouldBe listOf("build-a", "build-b")
    }

    @Test
    fun `onDisconnect clears last completed build snapshot for disconnected project`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            connection = Proxy.newProxyInstance(
                ProjectConnection::class.java.classLoader,
                arrayOf(ProjectConnection::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as ProjectConnection,
            projectDirectory = projectA,
        )
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

private fun blockingProjectConnection(
    buildEntered: CountDownLatch,
    releaseBuild: CountDownLatch,
): ProjectConnection =
    Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "newBuild" -> chainingProxy(
                    Class.forName("org.gradle.tooling.BuildLauncher"),
                    onRun = {
                        buildEntered.countDown()
                        releaseBuild.await(5, TimeUnit.SECONDS)
                    },
                )
                else -> defaultProxyReturn(method)
            }
        },
    ) as ProjectConnection

private fun chainingProxy(
    interfaceClass: Class<*>,
    onRun: () -> Unit,
): Any {
    val self = arrayOfNulls<Any>(1)
    self[0] = Proxy.newProxyInstance(
        interfaceClass.classLoader,
        arrayOf(interfaceClass),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "run" -> {
                    onRun()
                    null
                }
                else -> self[0]
            }
        },
    )
    return self[0]!!
}

private fun defaultProxyReturn(method: Method): Any? =
    when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE, java.lang.Long.TYPE, java.lang.Short.TYPE, java.lang.Byte.TYPE -> 0
        java.lang.Double.TYPE, java.lang.Float.TYPE -> 0
        else -> null
    }
