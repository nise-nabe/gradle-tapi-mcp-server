package com.example.gradle.mcp.build

import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.blockingProjectConnection
import com.example.gradle.mcp.support.queuedTracker
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.seedNoopConnection
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BuildExecutionManagerQueueTest {
    @Test
    fun `queueIfBusy enqueues when project already running`() {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(buildEntered, releaseBuild),
        )
        val manager = BuildExecutionManager(connectionManager)
        val request = BuildRunRequest(
            projectDirectory = testProjectDirectory,
            kind = BuildKind.TASKS,
            tasks = listOf("compileKotlin"),
        )

        try {
            val running = manager.startBackground(request, notifier = null, queueIfBusy = false)
            running["status"] shouldBe BuildProgressTracker.STATUS_RUNNING
            buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val queued = manager.startBackground(
                request = request.copy(tasks = listOf("test")),
                notifier = null,
                queueIfBusy = true,
            )
            queued["status"] shouldBe BuildProgressTracker.STATUS_QUEUED
            queued["queuePosition"] shouldBe 1
            queued["queuedBehindBuildId"] shouldBe running["buildId"]

            val status = manager.status(
                buildId = queued["buildId"] as String,
                outputLimit = com.example.gradle.mcp.model.OutputLimitOptions(),
                progressOptions = com.example.gradle.mcp.protocol.ProgressResponseOptions(),
            )
            status["status"] shouldBe BuildProgressTracker.STATUS_QUEUED
            status["queuePosition"] shouldBe 1
        } finally {
            releaseBuild.countDown()
            manager.shutdown()
        }
    }

    @Test
    fun `queued build starts after running build finishes`() {
        val releaseImmediately = CountDownLatch(1).also { it.countDown() }
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(CountDownLatch(1), releaseImmediately),
        )
        val manager = BuildExecutionManager(connectionManager)
        val request = BuildRunRequest(
            projectDirectory = testProjectDirectory,
            kind = BuildKind.TASKS,
            tasks = listOf("second"),
        )
        val running = testBuildRecord(
            id = "running-build",
            tracker = runningTracker(),
            projectDirectory = testProjectDirectory.absolutePath,
        )
        manager.seedRunningBuildForTests(running)

        val queued = manager.startBackground(request, notifier = null, queueIfBusy = true)
        queued["status"] shouldBe BuildProgressTracker.STATUS_QUEUED

        manager.completeBuildForTests("running-build").shouldBeTrue()
        waitUntilStatus(
            manager,
            queued["buildId"] as String,
            BuildProgressTracker.STATUS_RUNNING,
        )
        manager.hasQueuedBuild(testProjectDirectory).shouldBeFalse()
    }

    @Test
    fun `multiple queued builds preserve fifo order`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(CountDownLatch(1), CountDownLatch(1)),
        )
        val manager = BuildExecutionManager(connectionManager)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )

        val first = manager.startBackground(
            request = BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TASKS,
                tasks = listOf("first-queued"),
            ),
            notifier = null,
            queueIfBusy = true,
        )
        val second = manager.startBackground(
            request = BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TASKS,
                tasks = listOf("second-queued"),
            ),
            notifier = null,
            queueIfBusy = true,
        )

        first["queuePosition"] shouldBe 1
        second["queuePosition"] shouldBe 2
        second["queuedBehindBuildId"] shouldBe first["buildId"]
    }

    @Test
    fun `parallel queueIfBusy calls enqueue instead of rejecting`() {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(buildEntered, releaseBuild),
        )
        val manager = BuildExecutionManager(connectionManager)
        val request = BuildRunRequest(
            projectDirectory = testProjectDirectory,
            kind = BuildKind.TASKS,
            tasks = listOf("compileKotlin"),
        )

        try {
            manager.startBackground(request, notifier = null, queueIfBusy = false)
            buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val outcomes = runBlocking {
                listOf(
                    async(Dispatchers.Default) {
                        runCatching {
                            manager.startBackground(request.copy(tasks = listOf("a")), null, queueIfBusy = true)
                        }
                    },
                    async(Dispatchers.Default) {
                        runCatching {
                            manager.startBackground(request.copy(tasks = listOf("b")), null, queueIfBusy = true)
                        }
                    },
                ).awaitAll()
            }

            outcomes.count { it.isSuccess } shouldBe 2
            outcomes.forEach { result ->
                result.getOrThrow()["status"] shouldBe BuildProgressTracker.STATUS_QUEUED
            }
        } finally {
            releaseBuild.countDown()
            manager.shutdown()
        }
    }

    @Test
    fun `cancel queued build returns terminal cancelled immediately`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(CountDownLatch(1), CountDownLatch(1)),
        )
        val manager = BuildExecutionManager(connectionManager)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )
        manager.seedQueuedBuildForTests(
            testBuildRecord(
                id = "queued-build",
                tracker = queuedTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
            request = BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TASKS,
                tasks = listOf("test"),
            ),
        )

        val response = manager.cancelBuild("queued-build")
        response["terminalStatus"] shouldBe BuildProgressTracker.STATUS_CANCELLED
        response["cancelled"] shouldBe true
        manager.status(
            buildId = "queued-build",
            outputLimit = com.example.gradle.mcp.model.OutputLimitOptions(),
            progressOptions = com.example.gradle.mcp.protocol.ProgressResponseOptions(),
        )["status"] shouldBe BuildProgressTracker.STATUS_CANCELLED
    }

    @Test
    fun `queue full rejects with BUILD_QUEUE_FULL`() {
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(CountDownLatch(1), CountDownLatch(1)),
        )
        val manager = BuildExecutionManager(connectionManager)
        manager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )
        repeat(3) { index ->
            manager.seedQueuedBuildForTests(
                testBuildRecord(
                    id = "queued-$index",
                    tracker = queuedTracker(),
                    projectDirectory = testProjectDirectory.absolutePath,
                ),
                request = BuildRunRequest(
                    projectDirectory = testProjectDirectory,
                    kind = BuildKind.TASKS,
                    tasks = listOf("task-$index"),
                ),
            )
        }

        val error = shouldThrow<McpException> {
            manager.startBackground(
                request = BuildRunRequest(
                    projectDirectory = testProjectDirectory,
                    kind = BuildKind.TASKS,
                    tasks = listOf("overflow"),
                ),
                notifier = null,
                queueIfBusy = true,
            )
        }
        error.code shouldBe McpErrorCode.BUILD_QUEUE_FULL
        error.message.shouldContain("Build queue is full")
    }

    @Test
    fun `hasActiveBuild is true while build is queued`() {
        val connectionManager = GradleConnectionManager()
        val manager = BuildExecutionManager(connectionManager)
        manager.seedQueuedBuildForTests(
            testBuildRecord(
                id = "queued-build",
                tracker = queuedTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
            request = BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TASKS,
                tasks = listOf("test"),
            ),
        )

        manager.hasActiveBuild(testProjectDirectory).shouldBeTrue()
        manager.hasRunningBuild(testProjectDirectory).shouldBeFalse()
    }

    @Test
    fun `cancel queued build drains remaining queue when no running build`() {
        val releaseImmediately = CountDownLatch(1).also { it.countDown() }
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(CountDownLatch(1), releaseImmediately),
        )
        val manager = BuildExecutionManager(connectionManager)
        manager.seedQueuedBuildForTests(
            testBuildRecord(
                id = "queued-head",
                tracker = queuedTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
            request = BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TASKS,
                tasks = listOf("head"),
            ),
        )
        val remaining = manager.startBackground(
            request = BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TASKS,
                tasks = listOf("remaining"),
            ),
            notifier = null,
            queueIfBusy = true,
        )
        remaining["status"] shouldBe BuildProgressTracker.STATUS_QUEUED
        remaining["queuePosition"] shouldBe 2

        manager.cancelBuild("queued-head")

        waitUntilStatus(
            manager,
            remaining["buildId"] as String,
            BuildProgressTracker.STATUS_RUNNING,
        )
        manager.hasQueuedBuild(testProjectDirectory).shouldBeFalse()
    }

    @Test
    fun `cancel after dequeue promotes to running cancellation path`() {
        val connectionManager = GradleConnectionManager()
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        connectionManager.seedConnectionForTests(
            blockingProjectConnection(buildEntered, releaseBuild),
        )
        val manager = BuildExecutionManager(connectionManager)
        try {
            manager.seedRunningBuildForTests(
                testBuildRecord(
                    id = "running-build",
                    tracker = runningTracker(),
                    projectDirectory = testProjectDirectory.absolutePath,
                ),
            )
            val queued = manager.startBackground(
                request = BuildRunRequest(
                    projectDirectory = testProjectDirectory,
                    kind = BuildKind.TASKS,
                    tasks = listOf("next"),
                ),
                notifier = null,
                queueIfBusy = true,
            )
            val queuedId = queued["buildId"] as String
            manager.completeBuildForTests("running-build").shouldBeTrue()
            waitUntilStatus(manager, queuedId, BuildProgressTracker.STATUS_RUNNING)
            buildEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val response = manager.cancelBuild(queuedId)
            response["status"] shouldBe BuildProgressTracker.STATUS_RUNNING
            response["cancelled"] shouldBe null
            response["message"].toString().shouldContain("Cancellation requested")
        } finally {
            releaseBuild.countDown()
            manager.shutdown()
        }
    }

    @Test
    fun `queueIfBusy false still rejects when project busy`() {
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
            manager.startBackground(
                request = BuildRunRequest(
                    projectDirectory = testProjectDirectory,
                    kind = BuildKind.TASKS,
                    tasks = listOf("test"),
                ),
                notifier = null,
                queueIfBusy = false,
            )
        }
        error.code shouldBe McpErrorCode.BUILD_ALREADY_RUNNING
    }

    private fun waitUntilStatus(
        manager: BuildExecutionManager,
        buildId: String,
        expectedStatus: String,
        timeoutMs: Long = 5_000,
    ) {
        var waitedMs = 0L
        while (waitedMs < timeoutMs) {
            val status = manager.status(
                buildId = buildId,
                outputLimit = com.example.gradle.mcp.model.OutputLimitOptions(),
                progressOptions = com.example.gradle.mcp.protocol.ProgressResponseOptions(),
            )["status"]
            if (status == expectedStatus) {
                return
            }
            Thread.sleep(50)
            waitedMs += 50
        }
        val latest = manager.status(
            buildId = buildId,
            outputLimit = com.example.gradle.mcp.model.OutputLimitOptions(),
            progressOptions = com.example.gradle.mcp.protocol.ProgressResponseOptions(),
        )
        val actual = latest["status"]
        throw AssertionError(
            "Expected status $expectedStatus but was $actual for build $buildId" +
                (latest["error"]?.let { ", error=$it" } ?: ""),
        )
    }
}
