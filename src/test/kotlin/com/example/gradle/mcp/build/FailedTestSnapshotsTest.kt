package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.failedTestSnapshot
import com.example.gradle.mcp.support.testFailProgressEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FailedTestSnapshotsTest {
    @Test
    fun `mergeDistinct refreshes insertion order so takeLast keeps the most recent failure`() {
        val initialFailures = (1..11).map { index ->
            failedTestSnapshot(
                className = "com.example.Test$index",
                methodName = "fails$index",
                failureMessage = "first failure $index",
            )
        }
        val retriedFailure = initialFailures.first().copy(failureMessage = "latest failure")

        val merged = FailedTestSnapshots.mergeDistinct(initialFailures, listOf(retriedFailure))
            .takeLast(ProgressResponseOptions.MAX_RECENT_EVENTS_IN_RESPONSE)

        merged.map { it.failureMessage } shouldContainExactly (3..11).map { "first failure $it" } +
            listOf("latest failure")
    }

    @Test
    fun `fromEvents refreshes insertion order when the same test fails again`() {
        val events = buildList {
            for (index in 1..11) {
                add(testFailProgressEvent("com.example.Test$index", "fails$index", "first failure $index"))
            }
            add(testFailProgressEvent("com.example.Test1", "fails1", "latest failure"))
        }

        val failedTests = FailedTestSnapshots.fromEvents(events)
            .takeLast(ProgressResponseOptions.MAX_RECENT_EVENTS_IN_RESPONSE)

        failedTests.map { it.failureMessage } shouldContainExactly (3..11).map { "first failure $it" } +
            listOf("latest failure")
    }

    @Test
    fun `testFailureMaps includes failures without a method name`() {
        val maps = FailedTestSnapshots.testFailureMaps(
            listOf(
                failedTestSnapshot(
                    className = "com.example.FooTest",
                    methodName = "bar",
                    failureMessage = "boom",
                ),
                FailedTestSnapshot(
                    className = "com.example.FooTest",
                    displayName = "com.example.FooTest",
                    failureMessage = "setup failed",
                    failureType = "framework",
                ),
            ),
        )

        maps.size shouldBe 2
        maps[0]["methodName"] shouldBe "bar"
        maps[1].containsKey("methodName") shouldBe false
        maps[1]["failureType"] shouldBe "framework"
    }

    @Test
    fun `foldOutcomes drops a failure that later passes and ignores detail-less containers`() {
        val methodFailure = testFailProgressEvent("com.example.FlakyTest", "failsThenPasses()", "first attempt")
        val passed = methodFailure.copy(eventType = ProgressEventTypes.TEST_SUCCESS, outcome = null)
        val container = ProgressEventSnapshot(
            timestamp = "2026-06-14T10:00:01Z",
            eventType = ProgressEventTypes.TEST_FAIL,
            displayName = "Gradle Test Run :test failed",
            outcome = "failed",
        )
        val stillFailing = testFailProgressEvent("com.example.AlwaysFailTest", "alwaysFails()", "genuine failure")

        val outcomes = FailedTestSnapshots.foldOutcomes(listOf(methodFailure, container, passed, stillFailing))

        outcomes.failedTests.map { it.methodName } shouldContainExactly listOf("alwaysFails()")
        outcomes.flakyTests.map { it.methodName } shouldContainExactly listOf("failsThenPasses()")
        outcomes.flakyTests.single().failureMessage shouldBe "first attempt"
    }

    @Test
    fun `mergeOutcomes keeps failureType when the later snapshot omits it`() {
        val earlier = FailedTestSnapshot(
            className = "com.example.FooTest",
            methodName = "bar",
            displayName = "com.example.FooTest.bar",
            failureMessage = "first",
            exceptionType = "junit.framework.AssertionFailedError",
            failureType = "assertion",
            sourceFile = "FooTest.kt",
            line = 12,
        )
        val later = earlier.copy(
            failureMessage = "latest failure",
            exceptionType = null,
            failureType = null,
            sourceFile = null,
            line = null,
        )

        val merged = FailedTestSnapshots.mergeOutcomes(
            failedLists = listOf(listOf(earlier), listOf(later)),
            flakyLists = emptyList(),
        ).failedTests.single()

        merged.failureMessage shouldBe "latest failure"
        merged.failureType shouldBe "assertion"
        merged.exceptionType shouldBe "junit.framework.AssertionFailedError"
        merged.sourceFile shouldBe "FooTest.kt"
        merged.line shouldBe 12
    }

    @Test
    fun `mergeOutcomes keeps failureType when a passed retry omits it`() {
        val failed = FailedTestSnapshot(
            className = "com.example.FooTest",
            methodName = "bar",
            displayName = "com.example.FooTest.bar",
            failureMessage = "first attempt",
            failureType = "assertion",
        )
        val flaky = failed.copy(failureMessage = null, failureType = null)

        val outcomes = FailedTestSnapshots.mergeOutcomes(
            failedLists = listOf(listOf(failed)),
            flakyLists = listOf(listOf(flaky)),
        )

        outcomes.failedTests.shouldBeEmpty()
        outcomes.flakyTests.single().failureType shouldBe "assertion"
        outcomes.flakyTests.single().failureMessage shouldBe "first attempt"
    }

    @Test
    fun `fromEvents caps tracked failed tests`() {
        val events = (1..11).map { index ->
            testFailProgressEvent("com.example.Test$index", "fails$index", "failure $index")
        }

        FailedTestSnapshots.fromEvents(events).size shouldBe FailedTestSnapshots.MAX_TRACKED_FAILED_TESTS
    }
}
