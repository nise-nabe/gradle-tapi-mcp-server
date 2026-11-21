package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.BuildKind
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.FailedTestSnapshot
import com.example.gradle.mcp.build.persistence.McpBuildRecordPaths
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.encodeMcpJson
import com.example.gradle.mcp.support.failedTracker
import com.example.gradle.mcp.support.gradleBuildResult
import com.example.gradle.mcp.support.mcpBuildResult
import com.example.gradle.mcp.support.persistedBuildManager
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.seedNoopConnection
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.withWorkspaceDirectory
import com.example.gradle.mcp.support.writeDiskFile
import com.example.gradle.mcp.support.writeGradleResultToDisk
import com.example.gradle.mcp.support.writeMcpResultToDisk
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.tooling.Failure
import org.gradle.tooling.TestAssertionFailure
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestStartEvent
import org.gradle.tooling.events.test.TestSuccessResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.time.Instant

class BuildExecutionManagerPersistenceTest {
    private val manager = BuildExecutionManager(GradleConnectionManager())

    @Test
    fun `status returns not_found for unknown build`() {
        val result = manager.status("missing-build-id", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "not_found"
    }

    @Test
    fun `status loads persisted build from project gradle directory`(@TempDir projectDir: File) {
        val (persistedManager, store) = persistedBuildManager(projectDir)
        val buildId = "disk-only-build"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                buildSummary = mapOf("resultLine" to "BUILD SUCCESSFUL in 1s"),
            ),
        )

        val result = persistedManager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "succeeded"
        result["outcome"] shouldBe "SUCCESS"
        result["buildId"] shouldBe buildId
    }

    @Test
    fun `status restores taskPathInferred from persisted mcp result`(@TempDir projectDir: File) {
        val (persistedManager, store) = persistedBuildManager(projectDir)
        val buildId = "inferred-disk-build"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                kind = "tests",
                tasks = listOf(":app:test"),
                testClasses = listOf("com.example.app.FooTest"),
                taskPath = ":app:test",
                taskPathInferred = true,
            ),
        )

        val result = persistedManager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

        result["taskPath"] shouldBe ":app:test"
        result["taskPathInferred"] shouldBe true
    }

    @Test
    fun `status loads persisted build from workspace env when disconnected`(@TempDir projectDir: File) {
        val store = com.example.gradle.mcp.build.persistence.BuildRecordStore()
        val manager = BuildExecutionManager(GradleConnectionManager(), store)
        val buildId = "workspace-disk-build"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(buildId = buildId, projectDirectory = projectDir.absolutePath),
        )

        withWorkspaceDirectory(projectDir) {
            val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())

            result["status"] shouldBe "succeeded"
            result["buildId"] shouldBe buildId
        }
    }

    @Test
    fun `status rejects path traversal buildId`(@TempDir projectDir: File) {
        val (manager, _) = persistedBuildManager(projectDir)
        val leakedDir = File(projectDir, ".gradle/leaked-build")
        leakedDir.mkdirs()
        File(leakedDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
                mcpBuildResult(buildId = "leaked", projectDirectory = projectDir.absolutePath),
            ),
            StandardCharsets.UTF_8,
        )

        val result = manager.status("../leaked-build", OutputLimitOptions(), ProgressResponseOptions())

        result["status"] shouldBe "not_found"
    }

    @Test
    fun `status prefers disk gradle succeeded over memory failed after disconnect`(@TempDir projectDir: File) {
        val buildId = "disconnect-merge-build"
        val (manager, store) = persistedBuildManager(projectDir)
        val streams = CapturingStreams().also {
            it.appendStdoutForTests("partial\nBUILD SUCCESSFUL in 2s\n2 actionable tasks: 2 executed\n")
        }
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = failedTracker(message = "Gradle connection closed"),
                streams = streams,
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                errorMessage = "Gradle connection closed"
            },
        )
        store.writeDiskFile(projectDir, buildId, McpBuildRecordPaths.STDOUT_LOG, "partial\n")
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "succeeded",
                finishedAt = "2026-06-14T10:02:00Z",
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                status = "failed",
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
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
        val (manager, store) = persistedBuildManager(projectDir)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = failedTracker(message = "Gradle connection closed"),
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T10:01:00Z")
                errorMessage = "Gradle connection closed"
            },
        )
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "running",
                taskNames = listOf("build"),
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                finishedAt = Instant.now().minusSeconds(5).toString(),
                status = "failed",
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
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
        connectionManager.seedNoopConnection(projectB)
        val store = com.example.gradle.mcp.build.persistence.BuildRecordStore()
        val manager = BuildExecutionManager(connectionManager, store)
        val buildId = "cross-project-build"
        store.writeMcpResultToDisk(
            projectA,
            mcpBuildResult(buildId = buildId, projectDirectory = projectA.absolutePath),
        )

        manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())["status"] shouldBe "not_found"

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
        val (manager, store) = persistedBuildManager(projectDir)
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "running",
                taskNames = listOf("build"),
            ),
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
    fun `status merges disk progress while in-memory build is still running`(@TempDir projectDir: File) {
        val buildId = "active-running-build"
        val (manager, store) = persistedBuildManager(projectDir)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = runningTracker(),
                projectDirectory = projectDir.absolutePath,
            ),
        )
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "running",
                taskNames = listOf("build"),
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                finishedAt = Instant.now().minusSeconds(120).toString(),
                status = "failed",
                outcome = "FAILED",
                error = "Gradle connection closed",
            ),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            """{"ts":"2026-06-14T10:00:30Z","type":"TASK_START","displayName":":app:build"}""" + "\n",
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())
        val withProgress = manager.status(
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(includeProgress = true),
        )

        result["status"] shouldBe "running"
        result["statusSource"] shouldBe "memory"
        result.containsKey("error") shouldBe false
        result["recordDirectory"].shouldNotBeNull()
        val progress = withProgress["progress"] as Map<*, *>
        progress["status"] shouldBe "running"
        progress["totalEventCount"] shouldBe 2
        (progress["recentEvents"] as List<*>).map { (it as Map<*, *>)["displayName"] } shouldContain ":app:build"
    }

    @Test
    fun `status keeps failureType from memory when disk events omit it`(@TempDir projectDir: File) {
        val buildId = "memory-failure-type"
        val (manager, store) = persistedBuildManager(projectDir)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                kind = BuildKind.TESTS,
                tasks = listOf("test"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = trackerWithAssertionFailure(passOnRetry = false),
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T10:02:00Z")
                errorMessage = "There were failing tests"
            },
        )
        writeFailedGradleResult(store, projectDir, buildId)
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            testFailEvent(failureType = null, exceptionType = null, failureMessage = "latest failure") +
                buildFinishedEvent("failed"),
        )

        val failure = singleMap(manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions()), "testFailures")

        failure["className"] shouldBe "com.example.FooTest"
        failure["methodName"] shouldBe "bar"
        failure["message"] shouldBe "latest failure"
        failure["failureType"] shouldBe "assertion"
    }

    @Test
    fun `status keeps failureType on a passed retry when disk events omit it`(@TempDir projectDir: File) {
        val buildId = "memory-flaky-failure-type"
        val (manager, store) = persistedBuildManager(projectDir)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = buildId,
                kind = BuildKind.TESTS,
                tasks = listOf("test"),
                startedAt = Instant.parse("2026-06-14T10:00:00Z"),
                tracker = trackerWithAssertionFailure(passOnRetry = true),
                projectDirectory = projectDir.absolutePath,
            ) {
                finishedAt = Instant.parse("2026-06-14T10:02:00Z")
            },
        )
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "succeeded",
                finishedAt = "2026-06-14T10:02:00Z",
                taskNames = listOf("test"),
            ),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            testFailEvent(failureType = null, exceptionType = null, failureMessage = "first attempt") +
                """{"ts":"2026-06-14T10:01:30Z","type":"TEST_SUCCESS","displayName":"com.example.FooTest.bar","className":"com.example.FooTest","methodName":"bar"}""" +
                "\n" +
                buildFinishedEvent("succeeded"),
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())
        val flaky = singleMap(result, "flakyTests")

        result["status"] shouldBe "succeeded"
        result.containsKey("testFailures") shouldBe false
        flaky["methodName"] shouldBe "bar"
        flaky["failureType"] shouldBe "assertion"
        flaky["message"] shouldBe "first attempt"
    }

    @Test
    fun `status derives failureType from exceptionType on a disk-only read`(@TempDir projectDir: File) {
        val buildId = "disk-derived-failure-type"
        val (manager, store) = persistedBuildManager(projectDir)
        writeFailedGradleResult(store, projectDir, buildId)
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            testFailEvent(
                failureType = null,
                exceptionType = "junit.framework.AssertionFailedError",
                failureMessage = "expected:<1> but was:<0>",
            ) + buildFinishedEvent("failed"),
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())
        val failure = singleMap(result, "testFailures")

        result["status"] shouldBe "failed"
        result["statusSource"] shouldBe "disk"
        failure["className"] shouldBe "com.example.FooTest"
        failure["methodName"] shouldBe "bar"
        failure["message"] shouldBe "expected:<1> but was:<0>"
        failure["failureType"] shouldBe "assertion"
        failure["exceptionType"] shouldBe "junit.framework.AssertionFailedError"
    }

    @Test
    fun `status derives framework failureType for a non-assertion exception on disk`(@TempDir projectDir: File) {
        val buildId = "disk-framework-failure-type"
        val (manager, store) = persistedBuildManager(projectDir)
        writeFailedGradleResult(store, projectDir, buildId)
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            testFailEvent(
                failureType = null,
                exceptionType = "java.lang.IllegalStateException",
                failureMessage = "setup boom",
            ) + buildFinishedEvent("failed"),
        )

        val failure = singleMap(manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions()), "testFailures")

        failure["failureType"] shouldBe "framework"
        failure["exceptionType"] shouldBe "java.lang.IllegalStateException"
        failure["message"] shouldBe "setup boom"
    }

    @Test
    fun `status keeps a recorded failureType when exceptionType would classify differently`(@TempDir projectDir: File) {
        val buildId = "disk-explicit-failure-type"
        val (manager, store) = persistedBuildManager(projectDir)
        writeFailedGradleResult(store, projectDir, buildId)
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            testFailEvent(
                failureType = "framework",
                exceptionType = "junit.framework.AssertionFailedError",
                failureMessage = "setup used an assertion",
            ) + buildFinishedEvent("failed"),
        )

        val failure = singleMap(manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions()), "testFailures")

        failure["failureType"] shouldBe "framework"
    }

    @Test
    fun `status keeps mcp failureType when disk events have no exception type`(@TempDir projectDir: File) {
        val buildId = "disk-mcp-failure-type"
        val (manager, store) = persistedBuildManager(projectDir)
        writeFailedGradleResult(store, projectDir, buildId)
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                kind = "tests",
                tasks = listOf("test"),
                testClasses = listOf("com.example.FooTest"),
                status = "failed",
                outcome = "FAILED",
                error = "There were failing tests",
            ).copy(
                testFailures = listOf(
                    FailedTestSnapshot(
                        className = "com.example.FooTest",
                        methodName = "bar",
                        displayName = "com.example.FooTest.bar",
                        failureMessage = "from mcp",
                        exceptionType = "junit.framework.AssertionFailedError",
                        failureType = "assertion",
                    ),
                ),
                failedTestCount = 1,
                failedTestNames = listOf("com.example.FooTest.bar"),
            ),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            testFailEvent(failureType = null, exceptionType = null, failureMessage = "from events") +
                buildFinishedEvent("failed"),
        )

        val failure = singleMap(manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions()), "testFailures")

        failure["message"] shouldBe "from events"
        failure["failureType"] shouldBe "assertion"
        failure["exceptionType"] shouldBe "junit.framework.AssertionFailedError"
        failure["className"] shouldBe "com.example.FooTest"
        failure["methodName"] shouldBe "bar"
    }

    @Test
    fun `status derives failureType for a disk-only passed retry`(@TempDir projectDir: File) {
        val buildId = "disk-flaky-failure-type"
        val (manager, store) = persistedBuildManager(projectDir)
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "succeeded",
                finishedAt = "2026-06-14T10:02:00Z",
                taskNames = listOf("test"),
            ),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            testFailEvent(
                failureType = null,
                exceptionType = "org.opentest4j.AssertionFailedError",
                failureMessage = "first attempt",
            ) +
                """{"ts":"2026-06-14T10:01:30Z","type":"TEST_SUCCESS","displayName":"com.example.FooTest.bar","className":"com.example.FooTest","methodName":"bar"}""" +
                "\n" +
                buildFinishedEvent("succeeded"),
        )

        val result = manager.status(buildId, OutputLimitOptions(), ProgressResponseOptions())
        val flaky = singleMap(result, "flakyTests")

        result["status"] shouldBe "succeeded"
        result["statusSource"] shouldBe "disk"
        result.containsKey("testFailures") shouldBe false
        flaky["failureType"] shouldBe "assertion"
        flaky["message"] shouldBe "first attempt"
        flaky["methodName"] shouldBe "bar"
    }

    private fun writeFailedGradleResult(
        store: com.example.gradle.mcp.build.persistence.BuildRecordStore,
        projectDir: File,
        buildId: String,
    ) {
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "failed",
                finishedAt = "2026-06-14T10:02:00Z",
                failure = "There were failing tests",
                taskNames = listOf("test"),
            ),
        )
    }

    private fun testFailEvent(
        failureType: String?,
        exceptionType: String?,
        failureMessage: String,
    ): String {
        val fields = buildList {
            add(""""ts":"2026-06-14T10:01:00Z"""")
            add(""""type":"TEST_FAIL"""")
            add(""""displayName":"com.example.FooTest.bar"""")
            add(""""outcome":"$failureMessage"""")
            add(""""className":"com.example.FooTest"""")
            add(""""methodName":"bar"""")
            add(""""failureMessage":"$failureMessage"""")
            if (exceptionType != null) {
                add(""""exceptionType":"$exceptionType"""")
            }
            if (failureType != null) {
                add(""""failureType":"$failureType"""")
            }
        }
        return "{${fields.joinToString(",")}}\n"
    }

    private fun buildFinishedEvent(status: String): String =
        """{"ts":"2026-06-14T10:02:00Z","type":"BUILD_FINISHED","status":"$status"}""" + "\n"

    private fun singleMap(result: Map<String, Any?>, key: String): Map<*, *> =
        (result[key] as List<*>).single() as Map<*, *>

    private fun trackerWithAssertionFailure(passOnRetry: Boolean): BuildProgressTracker {
        val tracker = runningTracker()
        val listener = tracker.asGradleListener()
        val displayName = "com.example.FooTest.bar"
        val descriptor = jvmTestDescriptor(displayName)
        listener.statusChanged(testStart(displayName, descriptor))
        listener.statusChanged(
            testFinish(
                displayName,
                descriptor,
                testFailureResult("expected:<1> but was:<0>"),
            ),
        )
        if (passOnRetry) {
            listener.statusChanged(testStart(displayName, descriptor))
            listener.statusChanged(testFinish(displayName, descriptor, testSuccessResult()))
            tracker.markSucceeded()
        } else {
            tracker.markFailed("There were failing tests")
        }
        return tracker
    }

    private fun jvmTestDescriptor(displayName: String): JvmTestOperationDescriptor =
        Proxy.newProxyInstance(
            JvmTestOperationDescriptor::class.java.classLoader,
            arrayOf(JvmTestOperationDescriptor::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName", "getName", "getTestDisplayName" -> displayName
                    "getClassName" -> "com.example.FooTest"
                    "getMethodName" -> "bar"
                    "getParent" -> null
                    else -> null
                }
            },
        ) as JvmTestOperationDescriptor

    private fun testStart(displayName: String, descriptor: JvmTestOperationDescriptor): TestStartEvent =
        Proxy.newProxyInstance(
            TestStartEvent::class.java.classLoader,
            arrayOf(TestStartEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> displayName
                    "getEventTime" -> 0L
                    "getDescriptor" -> descriptor
                    else -> null
                }
            },
        ) as TestStartEvent

    private fun testFinish(
        displayName: String,
        descriptor: JvmTestOperationDescriptor,
        result: Any,
    ): TestFinishEvent =
        Proxy.newProxyInstance(
            TestFinishEvent::class.java.classLoader,
            arrayOf(TestFinishEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> displayName
                    "getEventTime" -> 1L
                    "getDescriptor" -> descriptor
                    "getResult" -> result
                    else -> null
                }
            },
        ) as TestFinishEvent

    private fun testFailureResult(message: String): TestFailureResult =
        Proxy.newProxyInstance(
            TestFailureResult::class.java.classLoader,
            arrayOf(TestFailureResult::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getFailures" -> listOf(assertionFailure(message))
                    "getStartTime", "getEndTime" -> 0L
                    else -> null
                }
            },
        ) as TestFailureResult

    private fun assertionFailure(message: String): Failure =
        Proxy.newProxyInstance(
            Failure::class.java.classLoader,
            arrayOf(TestAssertionFailure::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getMessage" -> message
                    "getDescription" -> "junit.framework.AssertionFailedError: $message"
                    "getClassName" -> "junit.framework.AssertionFailedError"
                    "getCauses", "getProblems" -> emptyList<Any>()
                    else -> null
                }
            },
        ) as Failure

    private fun testSuccessResult(): TestSuccessResult =
        Proxy.newProxyInstance(
            TestSuccessResult::class.java.classLoader,
            arrayOf(TestSuccessResult::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getStartTime", "getEndTime" -> 0L
                    else -> null
                }
            },
        ) as TestSuccessResult
}
