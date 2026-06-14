package com.example.gradle.mcp.build.persistence

import com.example.gradle.mcp.build.BuildKind
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.BuildRecord
import com.example.gradle.mcp.build.BuildStatusAssembler
import com.example.gradle.mcp.build.CapturingStreams
import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.mcpObjectMapper
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
        args shouldContain "--init-script"
        args.last().shouldContain("mcp-build-recorder")
    }

    @Test
    fun `writeMcpResult persists result and logs`(@TempDir projectDir: File) {
        val record = completedRecord(projectDir, "persisted-build")
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
            mcpObjectMapper().writeValueAsString(
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("build", "check"),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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
        loadStatus(projectDir, "missing", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
    }

    @Test
    fun `loadStatus prefers gradle terminal status over mcp running`(@TempDir projectDir: File) {
        val buildId = "gradle-wins"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
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
            mcpObjectMapper().writeValueAsString(
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """
            {"ts":"2026-06-14T10:00:01Z","type":"START","displayName":"Gradle tasks: build"}
            {"ts":"2026-06-14T10:00:02Z","type":"TASK_START","displayName":":app:compileJava"}
            {"ts":"2026-06-14T10:00:03Z","type":"TASK_SUCCESS","displayName":":app:compileJava"}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )

        val status = loadStatus(
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
            mcpObjectMapper().writeValueAsString(
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "succeeded"
        (status["buildSummary"] as Map<*, *>)["resultLine"] shouldBe "BUILD SUCCESSFUL in 1s"
    }

    @Test
    fun `loadStatus derives running test progress from test events ndjson`(@TempDir projectDir: File) {
        val buildId = "test-events-build"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
                GradleBuildResult(
                    buildId = buildId,
                    status = "running",
                    startedAt = "2026-06-14T10:00:00Z",
                    taskNames = listOf("test"),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(recordDir, McpBuildRecordPaths.EVENTS_FILE).writeText(
            """
            {"ts":"2026-06-14T10:00:01Z","type":"START","displayName":"Gradle tasks: test"}
            {"ts":"2026-06-14T10:00:02Z","type":"TEST_START","displayName":"com.example.FooTest.bar"}
            {"ts":"2026-06-14T10:00:03Z","type":"TEST_SUCCESS","displayName":"com.example.FooTest.bar"}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )

        val status = loadStatus(
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
    fun `loadStatus omits stale mcp error when gradle terminal status wins`(@TempDir projectDir: File) {
        val buildId = "gradle-succeeded-stale-mcp"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        File(recordDir, McpBuildRecordPaths.GRADLE_RESULT_FILE).writeText(
            mcpObjectMapper().writeValueAsString(
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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
            mcpObjectMapper().writeValueAsString(
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
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
            mcpObjectMapper().writeValueAsString(
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

        val status = loadStatus(projectDir, buildId, OutputLimitOptions(), ProgressResponseOptions())
            .shouldNotBeNull()

        status["status"] shouldBe "failed"
        status["failedTaskCount"] shouldBe 1
        status["failedTasks"] shouldBe listOf(":app:broken")
    }

    @Test
    fun `loadArtifacts restores stdout total chars from mcp result`(@TempDir projectDir: File) {
        val buildId = "truncated-stdout"
        val recordDir = store.recordDirectory(projectDir, buildId).shouldNotBeNull()
        recordDir.mkdirs()
        val tail = "BUILD SUCCESSFUL in 1s\n"
        File(recordDir, McpBuildRecordPaths.STDOUT_LOG).writeText(tail, StandardCharsets.UTF_8)
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

        loadStatus(projectDir, "../leaked-build", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
        loadStatus(projectDir, "..\\leaked-build", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
        loadStatus(projectDir, "foo/../leaked-build", OutputLimitOptions(), ProgressResponseOptions()).shouldBeNull()
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
                mcpObjectMapper().writeValueAsString(
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

        val artifacts = store.loadArtifacts(projectDir, buildId).shouldNotBeNull()

        artifacts.events shouldBe emptyList()
        artifacts.stdout.text shouldBe ""
        artifacts.gradleResult.shouldBeNull()
        artifacts.mcpResult.shouldNotBeNull()
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

    private fun loadStatus(
        projectDir: File,
        buildId: String,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
    ): Map<String, Any?>? {
        val artifacts = store.loadArtifacts(projectDir, buildId) ?: return null
        return BuildStatusAssembler.assemble(
            PersistedBuildViewFactory.fromArtifacts(buildId, artifacts),
            outputLimit,
            progressOptions,
        )
    }

    private fun completedRecord(projectDir: File, buildId: String): BuildRecord {
        val streams = CapturingStreams()
        streams.appendStdoutForTests("BUILD SUCCESSFUL in 1s\n")
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markSucceeded()
        return BuildRecord(
            id = buildId,
            kind = BuildKind.TASKS,
            tasks = listOf("build"),
            testClasses = emptyList(),
            startedAt = Instant.parse("2026-06-14T10:00:00Z"),
            progressTracker = tracker,
            streams = streams,
            projectDirectory = projectDir.absolutePath,
        ).also { it.finishedAt = Instant.parse("2026-06-14T10:01:00Z") }
    }
}
