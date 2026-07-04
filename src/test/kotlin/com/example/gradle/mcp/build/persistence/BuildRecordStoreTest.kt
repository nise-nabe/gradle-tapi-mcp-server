package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildKind
import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.TestRunSelection
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildRecord
import com.example.gradle.mcp.support.TEST_ISO_FINISH
import com.example.gradle.mcp.support.gradleBuildResult
import com.example.gradle.mcp.support.loadAssembledStatus
import com.example.gradle.mcp.support.mcpBuildResult
import com.example.gradle.mcp.support.succeededBuildRecord
import com.example.gradle.mcp.support.writeDiskFile
import com.example.gradle.mcp.support.writeGradleResultToDisk
import com.example.gradle.mcp.support.writeMcpResultToDisk
import com.example.gradle.mcp.build.CapturingStreams
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.encodeMcpJson
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import org.junit.jupiter.api.Assumptions.assumeTrue

class BuildRecordStoreTest {
    private val store = BuildRecordStore()

    @Test
    fun `launcherArguments reserves mcp properties and init script`(@TempDir projectDir: File) {
        val args = store.launcherArguments(projectDir, "build-1")

        args shouldContain "-Pmcp.buildId=build-1"
        args shouldContain "-Pmcp.recordDir=${File(projectDir, ".gradle/mcp-builds/build-1").absolutePath}"
        args.any { it.startsWith("-Pmcp.ccInitScript=") } shouldBe true
        args shouldContain "--init-script"
        args.last().shouldContain("mcp-build-recorder")
    }

    @Test
    fun `readMcpResult reconstructs method selection from persisted flat fields`(@TempDir projectDir: File) {
        val record = succeededBuildRecord(projectDir, "method-selection-build").copy(
            kind = BuildKind.TESTS,
            tasks = listOf(":app:test"),
            selection = TestRunSelection.Methods(
                methods = mapOf("com.example.FooTest" to listOf("method1")),
                taskPath = ":app:test",
            ),
        )
        store.writeMcpResult(record, record.progressTracker.snapshot())

        val mcpResult = store.readMcpResult(
            store.recordDirectory(projectDir, "method-selection-build").shouldNotBeNull(),
        ).shouldNotBeNull()

        mcpResult.testClasses shouldBe listOf("com.example.FooTest")
        mcpResult.testMethods shouldBe mapOf("com.example.FooTest" to listOf("method1"))
        mcpResult.selection shouldBe TestRunSelection.Methods(
            methods = mapOf("com.example.FooTest" to listOf("method1")),
            taskPath = ":app:test",
        )

        val json = File(
            store.recordDirectory(projectDir, "method-selection-build").shouldNotBeNull(),
            McpBuildRecordPaths.MCP_RESULT_FILE,
        ).readText(StandardCharsets.UTF_8)
        json.shouldContain("\"testMethods\"")
        json.contains("\"selection\"") shouldBe false
    }

    @Test
    fun `writeMcpResult persists result and logs`(@TempDir projectDir: File) {
        val record = succeededBuildRecord(projectDir, "persisted-build")
        store.writeMcpResult(record, record.progressTracker.snapshot())

        val recordDir = store.recordDirectory(projectDir, "persisted-build").shouldNotBeNull()
        store.readMcpResult(recordDir).shouldNotBeNull().apply {
            buildId shouldBe "persisted-build"
            status shouldBe BuildProgressTracker.STATUS_SUCCEEDED
            outcome shouldBe "SUCCESS"
            stdoutTotalChars shouldBe "BUILD SUCCESSFUL in 1s\n".length
        }
        File(recordDir, McpBuildRecordPaths.STDOUT_LOG).readText(StandardCharsets.UTF_8) shouldBe
            "BUILD SUCCESSFUL in 1s\n"
    }

    @Test
    fun `loadStatus merges gradle and mcp results`(@TempDir projectDir: File) {
        val buildId = "merged-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "succeeded",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        store.writeMcpResultFiles(
            recordDir = recordDir,
            result = McpBuildResult(
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
            stdout = com.example.gradle.mcp.build.CapturedStreamSnapshot("BUILD SUCCESSFUL in 1s\n", 24),
            stderr = com.example.gradle.mcp.build.CapturedStreamSnapshot("", 0),
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "succeeded"
        status["outcome"] shouldBe "SUCCESS"
        (status["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 1s"
        status["recordDirectory"] shouldBe recordDir.absolutePath
        status["statusSource"] shouldBe "disk"
    }

    @Test
    fun `loadStatus returns running from gradle result when mcp result is absent`(@TempDir projectDir: File) {
        val buildId = "running-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build", "check"),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "running"
        status["finishedAt"].shouldBeNull()
        status["tasks"] shouldBe listOf("build", "check")
        status["statusSource"] shouldBe "disk"
        status["liveProgress"] shouldBe false
        status["progressAvailable"] shouldBe false
        status.containsKey("progress") shouldBe false
    }

    @Test
    fun `loadStatus returns null for missing record directory`(@TempDir projectDir: File) {
        store.loadAssembledStatus(projectDir, "missing", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
    }

    @Test
    fun `loadStatus prefers gradle terminal status over mcp running`(@TempDir projectDir: File) {
        val buildId = "gradle-wins"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "failed",
                    finishedAt = "2026-06-14T10:01:00Z",
                    failure = "Execution failed for task ':app:compileJava'.",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                    status = "running",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "failed"
        status["error"] shouldBe "Execution failed for task ':app:compileJava'."
    }

    @Test
    fun `loadStatus derives running progress from events ndjson`(@TempDir projectDir: File) {
        val buildId = "events-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """
            {"ts":"2026-06-14T10:00:01Z","type":"START","displayName":"Gradle tasks: build"}
            {"ts":"2026-06-14T10:00:02Z","type":"TASK_START","displayName":":app:compileJava"}
            {"ts":"2026-06-14T10:00:03Z","type":"TASK_SUCCESS","displayName":":app:compileJava"}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(
            projectDir,
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(includeProgress = true),
        ).shouldNotBeNull()

        status["progressAvailable"] shouldBe true
        val progress = status["progress"] as Map<*, *>
        progress["completedTaskCount"] shouldBe 1
        progress["runningTaskCount"] shouldBe 0
    }

    @Test
    fun `loadStatus derives buildSummary from stdout when mcp result is absent`(@TempDir projectDir: File) {
        val buildId = "gradle-only-terminal"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "succeeded",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:01:00Z",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.STDOUT_LOG).writeText(
            "BUILD SUCCESSFUL in 1s\n",
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "succeeded"
        (status["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 1s"
    }

    @Test
    fun `loadStatus derives running test progress from test events ndjson`(@TempDir projectDir: File) {
        val buildId = "test-events-build"
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(buildId = buildId, status = "running", taskNames = listOf("test")),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            """
            {"ts":"2026-06-14T10:00:01Z","type":"START","displayName":"Gradle tasks: test"}
            {"ts":"2026-06-14T10:00:02Z","type":"TEST_START","displayName":"com.example.FooTest.bar","className":"com.example.FooTest","methodName":"bar"}
            {"ts":"2026-06-14T10:00:03Z","type":"TEST_SUCCESS","displayName":"com.example.FooTest.bar","className":"com.example.FooTest","methodName":"bar"}
            """.trimIndent() + "\n",
        )

        val status = store.loadAssembledStatus(
            projectDir,
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(includeProgress = true, includeTestDetails = true),
        ).shouldNotBeNull()

        status["progressAvailable"] shouldBe true
        val progress = status["progress"] as Map<*, *>
        progress["completedTaskCount"] shouldBe 1
        progress["runningTaskCount"] shouldBe 0
        val testDetails = (((progress["recentEvents"] as List<*>).last() as Map<*, *>)["test"] as Map<*, *>)
        testDetails["className"] shouldBe "com.example.FooTest"
        testDetails["methodName"] shouldBe "bar"
        testDetails.containsKey("sourcePath") shouldBe false
    }

    @Test
    fun `loadStatus omits stale mcp error when gradle terminal status wins`(@TempDir projectDir: File) {
        val buildId = "gradle-succeeded-stale-mcp"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "succeeded",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
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
                    failedTaskCount = 1,
                    failedTasks = listOf(":app:broken"),
                    buildSummary = mapOf("resultLine" to "BUILD FAILED"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.STDOUT_LOG).writeText(
            "BUILD SUCCESSFUL in 1s\n",
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "succeeded"
        status.containsKey("error") shouldBe false
        status.containsKey("failedTaskCount") shouldBe false
        (status["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 1s"
    }

    @Test
    fun `loadStatus keeps gradle startedAt and tasks on terminal gradle result only`(@TempDir projectDir: File) {
        val buildId = "gradle-terminal-metadata"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "succeeded",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                    taskNames = listOf("build", "check"),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["startedAt"] shouldBe "2026-06-14T10:00:00Z"
        status["tasks"] shouldBe listOf("build", "check")
    }

    @Test
    fun `loadStatus prefers gradle running over stale mcp failed after disconnect`(@TempDir projectDir: File) {
        val buildId = "gradle-running-stale-mcp-failed"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
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
            encodeMcpJson(
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

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "running"
        status["statusSource"] shouldBe "disk"
        status["liveProgress"] shouldBe false
        status.containsKey("finishedAt") shouldBe false
        status.containsKey("error") shouldBe false
        status.containsKey("outcome") shouldBe false
    }

    @Test
    fun `loadStatus prefers mcp failed when gradle running is stale`(@TempDir projectDir: File) {
        val buildId = "stale-gradle-running"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        val staleFinishedInstant = Instant.now().minusSeconds(120)
        val staleFinishedAt = staleFinishedInstant.toString()
        val preFinalizeEventTs = staleFinishedInstant.minusSeconds(30).toString()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
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
            encodeMcpJson(
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
            """{"ts":"$preFinalizeEventTs","type":"TASK_START","displayName":":app:build"}""" + "\n",
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "failed"
        status["outcome"] shouldBe "FAILED"
        status["error"] shouldBe "Gradle connection closed"
    }

    @Test
    fun `loadStatus keeps gradle running after disconnect when only pre-finalize events exist`(@TempDir projectDir: File) {
        val buildId = "disconnect-with-events"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        val recentFinishedAt = Instant.now().minusSeconds(5).toString()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
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
            encodeMcpJson(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = recentFinishedAt,
                    status = "failed",
                    outcome = "FAILED",
                    error = "Gradle connection closed",
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """
            {"ts":"2026-06-14T10:00:01Z","type":"START","displayName":"Gradle tasks: build"}
            {"ts":"2026-06-14T10:00:02Z","type":"TASK_START","displayName":":app:compileJava"}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "running"
        status["statusSource"] shouldBe "disk"
        status.containsKey("error") shouldBe false
    }

    @Test
    fun `loadStatus includes failed tasks from events on gradle terminal failed`(@TempDir projectDir: File) {
        val buildId = "gradle-failed-with-events"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "failed",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                    failure = "Execution failed for task ':app:broken'.",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """
            {"ts":"2026-06-14T10:01:00Z","type":"TASK_START","displayName":":app:broken"}
            {"ts":"2026-06-14T10:01:30Z","type":"TASK_FAIL","displayName":":app:broken","outcome":"broken"}
            {"ts":"2026-06-14T10:02:00Z","type":"BUILD_FINISHED","status":"failed"}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "failed"
        status["failedTaskCount"] shouldBe 1
        status["failedTasks"] shouldBe listOf(":app:broken")
    }

    @Test
    fun `loadStatus includes failedTests summary from events on gradle terminal failed`(@TempDir projectDir: File) {
        val buildId = "gradle-failed-with-test-details"
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "failed",
                finishedAt = "2026-06-14T10:02:00Z",
                failure = "Test failed",
                taskNames = listOf("test"),
            ),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            """
            {"ts":"2026-06-14T10:01:00Z","type":"TEST_FAIL","displayName":"com.example.FooTest.bar","outcome":"boom","className":"com.example.FooTest","methodName":"bar","failureMessage":"boom"}
            {"ts":"2026-06-14T10:02:00Z","type":"BUILD_FINISHED","status":"failed"}
            """.trimIndent() + "\n",
        )

        val status = store.loadAssembledStatus(
            projectDir,
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(includeTestDetails = true),
        ).shouldNotBeNull()

        status["status"] shouldBe "failed"
        ((((status["failedTests"] as List<*>).single() as Map<*, *>)["className"])) shouldBe "com.example.FooTest"
        ((((status["failedTests"] as List<*>).single() as Map<*, *>)["failureMessage"])) shouldBe "boom"
    }

    @Test
    fun `loadStatus includes failedTests from events on mcp terminal failed`(@TempDir projectDir: File) {
        val buildId = "mcp-failed-with-test-details"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                kind = "tests",
                tasks = emptyList(),
                testClasses = listOf("com.example.FooTest"),
                finishedAt = "2026-06-14T10:02:00Z",
                status = "failed",
                outcome = "FAILED",
                error = "Gradle connection closed",
                failedTaskCount = 1,
                failedTasks = listOf(":test"),
            ),
        )
        store.writeDiskFile(
            projectDir,
            buildId,
            McpBuildRecordPaths.EVENTS_FILE,
            """
            {"ts":"2026-06-14T10:01:00Z","type":"TEST_FAIL","displayName":"com.example.FooTest.bar","outcome":"boom","className":"com.example.FooTest","methodName":"bar","failureMessage":"boom"}
            """.trimIndent() + "\n",
        )

        val status = store.loadAssembledStatus(
            projectDir,
            buildId,
            OutputLimitOptions(),
            ProgressResponseOptions(includeTestDetails = true),
        ).shouldNotBeNull()

        status["status"] shouldBe "failed"
        status["failedTasks"] shouldBe listOf(":test")
        ((((status["failedTests"] as List<*>).single() as Map<*, *>)["failureMessage"])) shouldBe "boom"
    }

    @Test
    fun `loadStatus merges distinct persisted problems when gradle terminal failed`(@TempDir projectDir: File) {
        val buildId = "gradle-failed-with-multiple-persisted-problems"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "failed",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                    failure = "Execution failed for task ':app:broken'.",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """
            {"ts":"2026-06-14T10:01:00Z","type":"TASK_START","displayName":":app:broken"}
            {"ts":"2026-06-14T10:01:30Z","type":"TASK_FAIL","displayName":":app:broken","outcome":"broken"}
            {"ts":"2026-06-14T10:02:00Z","type":"BUILD_FINISHED","status":"failed"}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                    status = "failed",
                    outcome = "FAILED",
                    failedTaskCount = 1,
                    failedTasks = listOf(":app:broken"),
                    problems = listOf(
                        BuildProblemSnapshot(
                            label = "Compilation failed",
                            details = "cannot find symbol",
                            severity = "error",
                        ),
                        BuildProblemSnapshot(
                            label = "Missing dependency",
                            details = "Could not resolve com.example:lib:1.0",
                            severity = "error",
                        ),
                    ),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "failed"
        (status["problems"] as List<*>).map { (it as Map<*, *>)["label"] } shouldBe
            listOf("Compilation failed", "Missing dependency")
    }

    @Test
    fun `loadStatus includes persisted problems on gradle terminal failed`(@TempDir projectDir: File) {
        val buildId = "gradle-failed-with-persisted-problems"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            encodeMcpJson(
                GradleBuildResult(
                    buildId = buildId,
                    status = "failed",
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                    failure = "Execution failed for task ':app:broken'.",
                    taskNames = listOf("build"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """
            {"ts":"2026-06-14T10:01:00Z","type":"TASK_START","displayName":":app:broken"}
            {"ts":"2026-06-14T10:01:30Z","type":"TASK_FAIL","displayName":":app:broken","outcome":"broken"}
            {"ts":"2026-06-14T10:02:00Z","type":"BUILD_FINISHED","status":"failed"}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
                McpBuildResult(
                    buildId = buildId,
                    kind = "tasks",
                    tasks = listOf("build"),
                    testClasses = emptyList(),
                    projectDirectory = projectDir.absolutePath,
                    startedAt = "2026-06-14T10:00:00Z",
                    finishedAt = "2026-06-14T10:02:00Z",
                    status = "failed",
                    outcome = "FAILED",
                    failedTaskCount = 1,
                    failedTasks = listOf(":app:broken"),
                    problems = listOf(
                        BuildProblemSnapshot(
                            label = "Compilation failed",
                            details = "cannot find symbol",
                            severity = "error",
                            contextualLabel = "Task :app:broken",
                        ),
                    ),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val status = store.loadAssembledStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "failed"
        (status["problems"] as List<*>).single().let { problem ->
            (problem as Map<*, *>)["label"] shouldBe "Compilation failed"
            problem["details"] shouldBe "cannot find symbol"
            problem["severity"] shouldBe "error"
            problem["contextualLabel"] shouldBe "Task :app:broken"
        }
    }

    @Test
    fun `loadArtifacts restores stdout total chars from mcp result`(@TempDir projectDir: File) {
        val buildId = "truncated-stdout"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        val tail = "BUILD SUCCESSFUL in 1s\n"
        File(recordDir, McpBuildRecordPaths.STDOUT_LOG).writeText(tail, StandardCharsets.UTF_8)
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
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
                    stdoutTotalChars = 10_000,
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val artifacts = store.loadArtifacts(projectDir, buildId).shouldNotBeNull()

        artifacts.stdout.text shouldBe tail
        artifacts.stdout.totalChars shouldBe 10_000
    }

    @Test
    fun `loadStatus rejects unsafe buildId`(@TempDir projectDir: File) {
        val leakedDir = File(projectDir, ".gradle/leaked-build")
        leakedDir.mkdirs()
        File(leakedDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
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

        store.loadAssembledStatus(projectDir, "../leaked-build", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
        store.loadAssembledStatus(projectDir, "..\\leaked-build", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
        store.loadAssembledStatus(projectDir, "foo/../leaked-build", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
    }

    @Test
    fun `loadArtifacts ignores symlinked record files`(@TempDir projectDir: File) {
        val buildId = "symlink-record"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        val outside = File(projectDir, "outside-secret.txt").apply {
            writeText("TOP SECRET", StandardCharsets.UTF_8)
        }
        val outsideJson = File(projectDir, "outside.json").apply {
            writeText(
                encodeMcpJson(
                    GradleBuildResult(
                        buildId = buildId,
                        status = "succeeded",
                        finishedAt = "2026-06-14T10:01:00Z",
                    ),
                ),
                StandardCharsets.UTF_8,
            )
        }
        assumeSymbolicLink(File(recordDir, McpBuildRecordPaths.EVENTS_FILE), outside)
        assumeSymbolicLink(File(recordDir, McpBuildRecordPaths.STDOUT_LOG), outside)
        assumeSymbolicLink(File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE), outsideJson)
        File(recordDir, McpBuildRecordPaths.MCP_RESULT_FILE).writeText(
            encodeMcpJson(
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

        val artifacts = store.loadArtifacts(projectDir, buildId).shouldNotBeNull()

        artifacts.events shouldBe emptyList()
        artifacts.stdout.text shouldBe ""
        artifacts.gradleResult.shouldBeNull()
        artifacts.mcpResult.shouldNotBeNull()
    }

    @Test
    fun `recordDirectory rejects symlinked records root outside project`(@TempDir projectDir: File) {
        val outsideRoot = File(projectDir.parentFile, "outside-mcp-builds-${System.nanoTime()}").apply { mkdirs() }
        val gradleDir = File(projectDir, ".gradle").apply { mkdirs() }
        val mcpBuildsLink = File(gradleDir, "mcp-builds")
        assumeSymbolicLink(mcpBuildsLink, outsideRoot)

        McpBuildRecordPaths.recordDirectory(projectDir, "escape-build") shouldBe null
        store.loadArtifacts(projectDir, "escape-build") shouldBe null
    }

    @Test
    fun `isSafeBuildId accepts uuid and rejects traversal`() {
        McpBuildRecordPaths.isSafeBuildId("550e8400-e29b-41d4-a716-446655440000") shouldBe true
        McpBuildRecordPaths.isSafeBuildId("disk-only-build") shouldBe true
        McpBuildRecordPaths.isSafeBuildId("../escape") shouldBe false
        McpBuildRecordPaths.isSafeBuildId("..") shouldBe false
        McpBuildRecordPaths.isSafeBuildId("build/id") shouldBe false
        McpBuildRecordPaths.isSafeBuildId("build\\id") shouldBe false
        McpBuildRecordPaths.isSafeBuildId("") shouldBe false
    }

    private fun assumeSymbolicLink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {
            assumeTrue(false, "symlinks not supported on this platform")
        }
    }

    @Test
    fun `listBuildIds returns persisted build directories`(@TempDir projectDir: File) {
        val olderId = "older-build"
        val newerId = "newer-build"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = olderId,
                projectDirectory = projectDir.absolutePath,
                startedAt = "2026-06-14T09:00:00Z",
                finishedAt = "2026-06-14T09:01:00Z",
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = newerId,
                projectDirectory = projectDir.absolutePath,
                startedAt = "2026-06-14T10:00:00Z",
                finishedAt = "2026-06-14T10:01:00Z",
            ),
        )
        val emptyRecordDir = store.recordDirectory(projectDir, "empty-build").shouldNotBeNull()
        emptyRecordDir.mkdirs()

        store.listBuildIds(projectDir).toSet() shouldBe setOf(olderId, newerId)
    }

    @Test
    fun `loadListSummary resolves disk status from persistence contract`(@TempDir projectDir: File) {
        val buildId = "listed-build"
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(buildId = buildId, projectDirectory = projectDir.absolutePath),
        )

        val summary = store.loadListSummary(projectDir, buildId).shouldNotBeNull()

        summary.buildId shouldBe buildId
        summary.status shouldBe "succeeded"
        summary.outcome shouldBe "SUCCESS"
        summary.recordSource shouldBe "disk"
        summary.tasks shouldBe listOf("build")
    }

    @Test
    fun `loadListSummary outcome follows Gradle terminal authority over stale MCP outcome`(@TempDir projectDir: File) {
        val buildId = "gradle-failed-build"
        store.writeGradleResultToDisk(
            projectDir,
            buildId,
            gradleBuildResult(
                buildId = buildId,
                status = "failed",
                finishedAt = TEST_ISO_FINISH,
                taskNames = listOf("build"),
            ),
        )
        store.writeMcpResultToDisk(
            projectDir,
            mcpBuildResult(
                buildId = buildId,
                projectDirectory = projectDir.absolutePath,
                status = "failed",
                outcome = "SUCCESS",
            ),
        )

        val summary = store.loadListSummary(projectDir, buildId).shouldNotBeNull()

        summary.status shouldBe "failed"
        summary.outcome shouldBe "FAILED"
    }
}
