package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.TEST_ISO_FINISH
import com.example.gradle.mcp.support.TEST_ISO_START
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestSkippedResult
import org.gradle.tooling.events.test.TestSuccessResult
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.time.Instant

class TestMethodTimingsTest {
    @Test
    fun `no method events are a recorded empty capture`() {
        val timings = BuildProgressTracker().snapshot().testTimings

        timings.availability shouldBe TestTimingSnapshot.RECORDED
        timings.truncated.shouldBeFalse()
        timings.entries.shouldHaveSize(0)
    }

    @Test
    fun `records method timings ordered by start time with duration and task path`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        listener.statusChanged(
            finish(
                className = "com.example.LaterTest",
                methodName = "second",
                displayName = "second()",
                taskPath = ":app:test",
                result = "success",
                startTime = 2_000,
                endTime = 2_400,
            ),
        )
        listener.statusChanged(
            finish(
                className = "com.example.EarlyTest",
                methodName = "first",
                displayName = "first()",
                taskPath = ":app:test",
                result = "skipped",
                startTime = 1_000,
                endTime = 1_100,
            ),
        )
        listener.statusChanged(
            finish(
                className = "com.example.Suite",
                methodName = null,
                displayName = "suite",
                taskPath = ":app:test",
                result = "success",
                startTime = 0,
                endTime = 5_000,
            ),
        )

        val timings = tracker.snapshot().testTimings
        timings.availability shouldBe TestTimingSnapshot.RECORDED
        timings.truncated.shouldBeFalse()
        timings.entries.shouldHaveSize(2)
        timings.entries[0].methodName shouldBe "first"
        timings.entries[0].result shouldBe TestMethodTiming.RESULT_SKIPPED
        timings.entries[0].taskPath shouldBe ":app:test"
        timings.entries[0].durationMs shouldBe 100
        timings.entries[1].className shouldBe "com.example.LaterTest"
        timings.entries[1].displayName shouldBe "second()"
        timings.entries[1].durationMs shouldBe 400
        tracker.snapshot().recentEvents.map { it.displayName } shouldBe listOf("second()", "first()", "suite")
    }

    @Test
    fun `retries stay as separate entries`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        listener.statusChanged(
            finish(
                className = "com.example.FlakyTest",
                methodName = "flaky",
                displayName = "flaky()",
                taskPath = null,
                result = "failure",
                startTime = 500,
                endTime = 800,
            ),
        )
        listener.statusChanged(
            finish(
                className = "com.example.FlakyTest",
                methodName = "flaky",
                displayName = "flaky()",
                taskPath = null,
                result = "success",
                startTime = 100,
                endTime = 200,
            ),
        )

        val entries = tracker.snapshot().testTimings.entries
        entries.shouldHaveSize(2)
        entries[0].result shouldBe TestMethodTiming.RESULT_SUCCESS
        entries[0].startTimeEpochMs shouldBe 100
        entries[1].result shouldBe TestMethodTiming.RESULT_FAILURE
        entries[1].startTimeEpochMs shouldBe 500
        tracker.snapshot().failedTests.shouldHaveSize(0)
        tracker.snapshot().flakyTests.shouldHaveSize(1)
        tracker.snapshot().flakyTests.single().methodName shouldBe "flaky"
    }

    @Test
    fun `truncation keeps the earliest starts`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        repeat(TestTimingSnapshot.MAX_ENTRIES) { index ->
            listener.statusChanged(timedSuccess(methodName = "m$index", startTime = index.toLong()))
        }
        listener.statusChanged(timedSuccess(methodName = "late", startTime = 10_000))
        listener.statusChanged(timedSuccess(methodName = "earlier", startTime = -1))

        val timings = tracker.snapshot().testTimings
        timings.truncated.shouldBeTrue()
        timings.entries.shouldHaveSize(TestTimingSnapshot.MAX_ENTRIES)
        timings.entries.first().methodName shouldBe "earlier"
        timings.entries.map { it.methodName }.contains("late") shouldBe false
        timings.entries.map { it.methodName }.contains("m${TestTimingSnapshot.MAX_ENTRIES - 1}") shouldBe false
    }

    @Test
    fun `cancelled test failures are not timings`() {
        val tracker = BuildProgressTracker()
        tracker.markCancellationRequested()
        tracker.asGradleListener().statusChanged(
            finish(
                className = "com.example.FooTest",
                methodName = "bar",
                displayName = "bar()",
                taskPath = ":test",
                result = "failure",
                startTime = 10,
                endTime = 20,
            ),
        )

        val timings = tracker.snapshot().testTimings
        timings.availability shouldBe TestTimingSnapshot.RECORDED
        timings.entries.shouldHaveSize(0)
        tracker.snapshot().recentEvents.last().eventType shouldBe ProgressEventTypes.TEST_CANCEL
    }

    @Test
    fun `cancellation skip is a skipped result whose clock straddles the request`() {
        val requestedAt = 5_000L
        isCancellationInterruptedSkip(skippedResult(1_000, 4_999), requestedAt).shouldBeFalse()
        isCancellationInterruptedSkip(skippedResult(1_000, 5_000), requestedAt).shouldBeTrue()
        isCancellationInterruptedSkip(skippedResult(5_000, 5_000), requestedAt).shouldBeFalse()
        isCancellationInterruptedSkip(skippedResult(5_001, 5_010), requestedAt).shouldBeFalse()
        isCancellationInterruptedSkip(skippedResult(1_000, 9_000), null).shouldBeFalse()
        isCancellationInterruptedSkip(
            resultProxy(TestSuccessResult::class.java, 1_000, 9_000) as TestSuccessResult,
            requestedAt,
        ).shouldBeFalse()
        isCancellationInterruptedSkip(
            proxy(
                TestSkippedResult::class.java,
                mapOf("getStartTime" to RuntimeException("no time"), "getEndTime" to 9_000L),
            ),
            requestedAt,
        ).shouldBeFalse()
    }

    @Test
    fun `in-progress skip after cancellation is omitted and earlier skips stay`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val alreadyFinished = System.currentTimeMillis() - 1_000
        listener.statusChanged(
            finish(
                className = "com.example.SampleTest",
                methodName = "assumption()",
                displayName = "assumption()",
                taskPath = ":test",
                result = "skipped",
                startTime = alreadyFinished - 400,
                endTime = alreadyFinished,
            ),
        )
        val runningSince = System.currentTimeMillis() - 12_000
        tracker.markCancellationRequested()
        val stoppedAt = System.currentTimeMillis() + 20
        listener.statusChanged(
            finish(
                className = "com.example.SampleTest",
                methodName = "slow()",
                displayName = "slow()",
                taskPath = ":test",
                result = "skipped",
                startTime = runningSince,
                endTime = stoppedAt,
            ),
        )
        val disabledAt = System.currentTimeMillis() + 1_000
        listener.statusChanged(
            finish(
                className = "com.example.SampleTest",
                methodName = "disabledCase()",
                displayName = "disabledCase()",
                taskPath = ":test",
                result = "skipped",
                startTime = disabledAt,
                endTime = disabledAt,
            ),
        )

        val timings = tracker.snapshot().testTimings
        timings.availability shouldBe TestTimingSnapshot.RECORDED
        timings.entries.map { it.methodName } shouldBe listOf("assumption()", "disabledCase()")
        timings.entries.map { it.result } shouldBe listOf(
            TestMethodTiming.RESULT_SKIPPED,
            TestMethodTiming.RESULT_SKIPPED,
        )
        timings.entries[0].durationMs shouldBe 400
        timings.entries[1].durationMs shouldBe 0
        val events = tracker.snapshot().recentEvents
        events.first { it.displayName == "slow()" }.eventType shouldBe ProgressEventTypes.TEST_CANCEL
        events.first { it.displayName == "assumption()" }.eventType shouldBe ProgressEventTypes.TEST_SKIP
        events.first { it.displayName == "disabledCase()" }.eventType shouldBe ProgressEventTypes.TEST_SKIP
    }

    @Test
    fun `success that finishes after cancellation request stays in timings`() {
        val tracker = BuildProgressTracker()
        val started = System.currentTimeMillis() - 2_000
        tracker.markCancellationRequested()
        tracker.asGradleListener().statusChanged(
            finish(
                className = "com.example.SampleTest",
                methodName = "fast()",
                displayName = "fast()",
                taskPath = ":test",
                result = "success",
                startTime = started,
                endTime = System.currentTimeMillis() + 20,
            ),
        )

        val timings = tracker.snapshot().testTimings
        timings.entries.shouldHaveSize(1)
        timings.entries.single().result shouldBe TestMethodTiming.RESULT_SUCCESS
        tracker.snapshot().recentEvents.last().eventType shouldBe ProgressEventTypes.TEST_SUCCESS
    }

    @Test
    fun `unreadable operation times are incomplete rather than an empty success`() {
        val tracker = BuildProgressTracker()
        tracker.asGradleListener().statusChanged(
            finish(
                className = "com.example.BrokenTest",
                methodName = "broken",
                displayName = "broken()",
                taskPath = null,
                result = "unreadable",
                startTime = 0,
                endTime = 0,
            ),
        )

        val timings = tracker.snapshot().testTimings
        timings.availability shouldBe TestTimingSnapshot.INCOMPLETE
        timings.entries.shouldHaveSize(0)
    }

    @Test
    fun `includeTestTimings off omits the array and on reports capture state`() {
        val recorded = TestTimingSnapshot(
            availability = TestTimingSnapshot.RECORDED,
            truncated = false,
            entries = listOf(
                TestMethodTiming(
                    className = "com.example.FooTest",
                    methodName = "bar",
                    displayName = "bar()",
                    taskPath = ":test",
                    result = TestMethodTiming.RESULT_SUCCESS,
                    startTimeEpochMs = 1_000,
                    endTimeEpochMs = 1_250,
                ),
            ),
        )
        val hidden = assemble(recorded, includeTestTimings = false)
        hidden.containsKey("testTimings").shouldBeFalse()
        hidden.containsKey("testTimingsTruncated").shouldBeFalse()
        hidden.containsKey("testTimingsAvailability").shouldBeFalse()

        val shown = assemble(recorded, includeTestTimings = true)
        val entry = (shown["testTimings"] as List<*>).single() as Map<*, *>
        entry["className"] shouldBe "com.example.FooTest"
        entry["methodName"] shouldBe "bar"
        entry["displayName"] shouldBe "bar()"
        entry["taskPath"] shouldBe ":test"
        entry["result"] shouldBe "success"
        entry["startTime"] shouldBe Instant.ofEpochMilli(1_000).toString()
        entry["endTime"] shouldBe Instant.ofEpochMilli(1_250).toString()
        entry["durationMs"] shouldBe 250
        shown["testTimingsTruncated"] shouldBe false
        shown["testTimingsAvailability"] shouldBe TestTimingSnapshot.RECORDED

        val unavailable = assemble(TestTimingSnapshot.UNAVAILABLE_SNAPSHOT, includeTestTimings = true)
        (unavailable["testTimings"] as List<*>).shouldHaveSize(0)
        unavailable["testTimingsAvailability"] shouldBe TestTimingSnapshot.UNAVAILABLE
    }

    @Test
    fun `terminal disk merge keeps in-memory timings`() {
        val captured = TestTimingSnapshot(
            availability = TestTimingSnapshot.RECORDED,
            entries = listOf(
                TestMethodTiming(
                    className = "com.example.FooTest",
                    methodName = "bar",
                    displayName = "bar()",
                    taskPath = ":test",
                    result = TestMethodTiming.RESULT_SUCCESS,
                    startTimeEpochMs = 10,
                    endTimeEpochMs = 20,
                ),
            ),
        )
        val merged = BuildStatusMerger.merge(
            statusView(BuildProgressTracker.STATUS_SUCCEEDED, BuildStatusView.SOURCE_MEMORY, captured),
            statusView(
                BuildProgressTracker.STATUS_SUCCEEDED,
                BuildStatusView.SOURCE_DISK,
                TestTimingSnapshot.UNAVAILABLE_SNAPSHOT,
            ),
        )

        merged.testTimings.availability shouldBe TestTimingSnapshot.RECORDED
        merged.testTimings.entries.single().methodName shouldBe "bar"
    }

    private fun timedSuccess(methodName: String, startTime: Long): TestFinishEvent =
        finish(
            className = "com.example.CapTest",
            methodName = methodName,
            displayName = methodName,
            taskPath = null,
            result = "success",
            startTime = startTime,
            endTime = startTime + 1,
        )

    private fun assemble(timings: TestTimingSnapshot, includeTestTimings: Boolean): Map<String, Any?> =
        BuildStatusAssembler.assemble(
            view = statusView(BuildProgressTracker.STATUS_SUCCEEDED, BuildStatusView.SOURCE_MEMORY, timings),
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(includeTestTimings = includeTestTimings),
            style = BuildStatusResponseStyle.FOREGROUND,
        )

    private fun statusView(
        status: String,
        statusSource: String,
        timings: TestTimingSnapshot,
    ): BuildStatusView =
        BuildStatusView(
            buildId = "build-1",
            kind = "tests",
            status = status,
            startedAt = TEST_ISO_START,
            finishedAt = if (status == BuildProgressTracker.STATUS_RUNNING) null else TEST_ISO_FINISH,
            tasks = listOf(":test"),
            error = null,
            outcome = if (status == BuildProgressTracker.STATUS_SUCCEEDED) "SUCCESS" else null,
            buildSummary = null,
            progress = BuildProgressSnapshot(
                status = status,
                currentOperation = null,
                completedTaskCount = 0,
                runningTaskCount = 0,
                failedTaskCount = 0,
                completedTasks = emptyList(),
                runningTasks = emptyList(),
                failedTasks = emptyList(),
                recentEvents = emptyList(),
                totalEventCount = 0,
                testTimings = timings,
            ),
            progressAvailable = true,
            stdout = CapturedStreamSnapshot(text = "", totalChars = 0),
            stderr = CapturedStreamSnapshot(text = "", totalChars = 0),
            statusSource = statusSource,
            testTimings = timings,
        )

    private fun finish(
        className: String,
        methodName: String?,
        displayName: String,
        taskPath: String?,
        result: String,
        startTime: Long,
        endTime: Long,
    ): TestFinishEvent {
        val task = taskPath?.let { path ->
            proxy(
                TaskOperationDescriptor::class.java,
                mapOf(
                    "getTaskPath" to path,
                    "getDisplayName" to path,
                    "getName" to path,
                    "getParent" to null,
                ),
            )
        }
        val descriptor = proxy(
            JvmTestOperationDescriptor::class.java,
            mapOf(
                "getClassName" to className,
                "getMethodName" to methodName,
                "getDisplayName" to displayName,
                "getName" to displayName,
                "getParent" to task,
                "getSource" to null,
            ),
        )
        val resultProxy = when (result) {
            "failure" -> resultProxy(TestFailureResult::class.java, startTime, endTime)
            "skipped" -> resultProxy(TestSkippedResult::class.java, startTime, endTime)
            "unreadable" -> proxy(
                TestSuccessResult::class.java,
                mapOf("getStartTime" to RuntimeException("no time"), "getEndTime" to endTime),
            )
            else -> resultProxy(TestSuccessResult::class.java, startTime, endTime)
        }
        return proxy(
            TestFinishEvent::class.java,
            mapOf(
                "getDisplayName" to displayName,
                "getEventTime" to endTime,
                "getDescriptor" to descriptor,
                "getResult" to resultProxy,
            ),
        )
    }

    private fun skippedResult(startTime: Long, endTime: Long): TestSkippedResult =
        resultProxy(TestSkippedResult::class.java, startTime, endTime) as TestSkippedResult

    private fun resultProxy(type: Class<*>, startTime: Long, endTime: Long): Any =
        proxy(
            type,
            mapOf(
                "getStartTime" to startTime,
                "getEndTime" to endTime,
                "getFailures" to emptyList<Any>(),
            ),
        )

    private fun <T> proxy(type: Class<T>, values: Map<String, Any?>): T =
        type.cast(
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                InvocationHandler { _, method, _ ->
                    if (!values.containsKey(method.name)) {
                        return@InvocationHandler null
                    }
                    val value = values.getValue(method.name)
                    if (value is RuntimeException) {
                        throw value
                    }
                    value
                },
            ),
        )
}
