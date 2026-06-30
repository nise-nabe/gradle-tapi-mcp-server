package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.ProgressResponseOptions
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FailedTestSnapshotsTest {
    @Test
    fun `mergeDistinct refreshes insertion order so takeLast keeps the most recent failure`() {
        val initialFailures = (1..11).map { index ->
            failedTest(
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
                add(testFailEvent("com.example.Test$index", "fails$index", "first failure $index"))
            }
            add(testFailEvent("com.example.Test1", "fails1", "latest failure"))
        }

        val failedTests = FailedTestSnapshots.fromEvents(events)
            .takeLast(ProgressResponseOptions.MAX_RECENT_EVENTS_IN_RESPONSE)

        failedTests.map { it.failureMessage } shouldContainExactly (3..11).map { "first failure $it" } +
            listOf("latest failure")
    }

    @Test
    fun `fromEvents caps tracked failed tests`() {
        val events = (1..11).map { index ->
            testFailEvent("com.example.Test$index", "fails$index", "failure $index")
        }

        FailedTestSnapshots.fromEvents(events).size shouldBe FailedTestSnapshots.MAX_TRACKED_FAILED_TESTS
    }

    private fun failedTest(
        className: String,
        methodName: String,
        failureMessage: String,
    ): FailedTestSnapshot =
        FailedTestSnapshot(
            className = className,
            methodName = methodName,
            displayName = "$className.$methodName",
            failureMessage = failureMessage,
        )

    private fun testFailEvent(
        className: String,
        methodName: String,
        failureMessage: String,
    ): ProgressEventSnapshot =
        ProgressEventSnapshot(
            timestamp = "2026-06-29T00:00:00Z",
            eventType = ProgressEventTypes.TEST_FAIL,
            displayName = "$className.$methodName",
            outcome = failureMessage,
            testDetails = TestProgressDetailsSnapshot(
                className = className,
                methodName = methodName,
                failureMessage = failureMessage,
            ),
        )
}
