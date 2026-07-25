package com.example.gradle.mcp.build

import com.example.gradle.mcp.support.queuedTracker
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ActiveBuildSnapshotTest {
    @Test
    fun `forProject prefers running build over queued`() {
        val running = testBuildRecord(
            id = "running-id",
            tracker = runningTracker(),
            projectDirectory = testProjectDirectory.absolutePath,
            tasks = listOf("compileKotlin"),
        )
        val queued = testBuildRecord(
            id = "queued-id",
            tracker = queuedTracker(),
            projectDirectory = testProjectDirectory.absolutePath,
            tasks = listOf("test"),
        )

        val snapshot = ActiveBuildSnapshot.forProject(
            builds = listOf(queued, running),
            projectDirectory = testProjectDirectory,
        )

        snapshot?.activeBuildId shouldBe "running-id"
        snapshot?.activeStatus shouldBe BuildProgressTracker.STATUS_RUNNING
        snapshot?.activeTasks shouldBe listOf("compileKotlin")
    }

    @Test
    fun `forProject prefers preferred queued build when no running build`() {
        val head = testBuildRecord(
            id = "queued-head",
            tracker = queuedTracker(),
            projectDirectory = testProjectDirectory.absolutePath,
            tasks = listOf("head"),
        )
        val tail = testBuildRecord(
            id = "queued-tail",
            tracker = queuedTracker(),
            projectDirectory = testProjectDirectory.absolutePath,
            tasks = listOf("tail"),
        )

        val snapshot = ActiveBuildSnapshot.forProject(
            builds = listOf(tail, head),
            projectDirectory = testProjectDirectory,
            preferredQueuedBuildId = "queued-head",
        )

        snapshot?.activeBuildId shouldBe "queued-head"
        snapshot?.activeStatus shouldBe BuildProgressTracker.STATUS_QUEUED
        snapshot?.activeTasks shouldBe listOf("head")
    }

    @Test
    fun `toErrorFields includes test selection`() {
        val snapshot = ActiveBuildSnapshot.fromRecord(
            testBuildRecord(
                id = "test-build",
                kind = BuildKind.TESTS,
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
                selection = TestRunSelection.Classes(
                    taskPath = ":app:fastTest",
                    classes = listOf("com.example.FooTest"),
                ),
            ),
        )

        val fields = snapshot.toErrorFields()
        fields shouldContain ("activeBuildId" to "test-build")
        fields shouldContain ("activeKind" to "tests")
        fields shouldContain ("activeStatus" to BuildProgressTracker.STATUS_RUNNING)
        fields shouldContain ("activeTestClasses" to listOf("com.example.FooTest"))
        fields shouldContain ("activeTaskPath" to ":app:fastTest")
    }
}
