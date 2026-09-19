package com.example.gradle.mcp.build

import com.example.gradle.mcp.connection.ProjectLifecycleLock
import com.example.gradle.mcp.support.queuedTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProjectBuildQueueTest {
    @Test
    fun `requeueAtFront restores a dequeued build to the head`() {
        val queue = ProjectBuildQueue()
        val tracker = queuedTracker()
        val record = testBuildRecord(
            id = "queued-build",
            tracker = tracker,
            projectDirectory = testProjectDirectory.absolutePath,
        )
        val queued = queuedBuild(record)

        synchronized(ProjectLifecycleLock.forProject(testProjectDirectory)) {
            queue.enqueue(testProjectDirectory, queued)
            queue.takeNextIfIdle(testProjectDirectory, hasRunningBuild = false) shouldBe
                ProjectBuildQueue.TakeResult.Ready(queued)

            queue.requeueAtFront(testProjectDirectory, queued)

            queue.headBuildId(testProjectDirectory) shouldBe "queued-build"
            queue.position(testProjectDirectory, "queued-build") shouldBe 1
        }
        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_QUEUED
    }

    @Test
    fun `requeueAtFront drops a build cancelled by a concurrent reset`() {
        val queue = ProjectBuildQueue()
        val tracker = queuedTracker()
        val record = testBuildRecord(
            id = "queued-build",
            tracker = tracker,
            projectDirectory = testProjectDirectory.absolutePath,
        )
        val queued = queuedBuild(record)

        synchronized(ProjectLifecycleLock.forProject(testProjectDirectory)) {
            queue.enqueue(testProjectDirectory, queued)
            queue.takeNextIfIdle(testProjectDirectory, hasRunningBuild = false)

            // A concurrent disconnect/reset cancels the dequeued build before the requeue lands.
            tracker.markCancelled("Gradle connection closed")

            queue.requeueAtFront(testProjectDirectory, queued)

            queue.position(testProjectDirectory, "queued-build").shouldBeNull()
            queue.headBuildId(testProjectDirectory).shouldBeNull()
        }
        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_CANCELLED
        queue.projectKeys().shouldBeEmpty()
    }

    @Test
    fun `requeueAtFront drops a build that already started`() {
        val queue = ProjectBuildQueue()
        val tracker = queuedTracker()
        val record = testBuildRecord(
            id = "queued-build",
            tracker = tracker,
            projectDirectory = testProjectDirectory.absolutePath,
        )
        val queued = queuedBuild(record)

        synchronized(ProjectLifecycleLock.forProject(testProjectDirectory)) {
            queue.enqueue(testProjectDirectory, queued)
            queue.takeNextIfIdle(testProjectDirectory, hasRunningBuild = false)

            // The submitted work began before the executor rejection was observed.
            tracker.markStarting("Gradle tasks: build")

            queue.requeueAtFront(testProjectDirectory, queued)

            queue.position(testProjectDirectory, "queued-build").shouldBeNull()
        }
        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_RUNNING
        queue.projectKeys().shouldBeEmpty()
    }

    private fun queuedBuild(record: BuildRecord): ProjectBuildQueue.QueuedBuild =
        ProjectBuildQueue.QueuedBuild(
            record = record,
            request = BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TASKS,
                tasks = listOf("build"),
            ),
            work = {},
        )
}
