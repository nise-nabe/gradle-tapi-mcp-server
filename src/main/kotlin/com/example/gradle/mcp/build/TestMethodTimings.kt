package com.example.gradle.mcp.build

import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestOperationResult
import org.gradle.tooling.events.test.TestSkippedResult
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One executed test method attempt. Times come from the Tooling API operation
 * result (`startTime` / `endTime`), not from when this process received the event.
 * Retries stay as separate entries. Order is start time, which is not a single
 * execution order when test workers run in parallel.
 */
data class TestMethodTiming(
    val className: String?,
    val methodName: String,
    val displayName: String,
    val taskPath: String?,
    val result: String,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
) {
    val durationMs: Long
        get() = endTimeEpochMs - startTimeEpochMs

    fun toResponseMap(): Map<String, Any?> =
        buildMap {
            className?.let { put("className", it) }
            put("methodName", methodName)
            put("displayName", displayName)
            taskPath?.let { put("taskPath", it) }
            put("result", result)
            put("startTime", Instant.ofEpochMilli(startTimeEpochMs).toString())
            put("endTime", Instant.ofEpochMilli(endTimeEpochMs).toString())
            put("durationMs", durationMs)
        }

    companion object {
        const val RESULT_SUCCESS = "success"
        const val RESULT_FAILURE = "failure"
        const val RESULT_SKIPPED = "skipped"
    }
}

/**
 * In-memory capture for one MCP build. Not written to `events.ndjson`.
 *
 * [RECORDED] with an empty list means no method-level finish events were seen,
 * which on `gradle_run_tasks` includes an UP-TO-DATE or FROM-CACHE test task.
 * `gradle_run_tests` re-executes the selected tests, so it does not take that path.
 * [INCOMPLETE] means at least one method finish arrived without readable start/end
 * times. [UNAVAILABLE] means this status view has no in-memory capture (disk-only poll).
 */
data class TestTimingSnapshot(
    val availability: String,
    val truncated: Boolean = false,
    val entries: List<TestMethodTiming> = emptyList(),
) {
    companion object {
        const val RECORDED = "recorded"
        const val UNAVAILABLE = "unavailable"
        const val INCOMPLETE = "incomplete"
        const val MAX_ENTRIES = 100

        val UNAVAILABLE_SNAPSHOT = TestTimingSnapshot(availability = UNAVAILABLE)
    }
}

internal class TestTimingCollector {
    private val entries = ArrayList<TestMethodTiming>(16)
    private var truncated = false
    private var incomplete = false

    fun record(event: TestFinishEvent) {
        val descriptor = event.descriptor as? JvmTestOperationDescriptor ?: return
        val methodName = descriptor.methodName?.takeIf { it.isNotBlank() } ?: return
        val result = try {
            event.result
        } catch (_: RuntimeException) {
            incomplete = true
            return
        }
        val startTime: Long
        val endTime: Long
        try {
            startTime = result.startTime
            endTime = result.endTime
        } catch (_: RuntimeException) {
            incomplete = true
            return
        }
        remember(
            TestMethodTiming(
                className = descriptor.className?.takeIf { it.isNotBlank() },
                methodName = methodName,
                displayName = event.displayName,
                taskPath = taskPathFromDescriptor(descriptor),
                result = resultName(result),
                startTimeEpochMs = startTime,
                endTimeEpochMs = endTime,
            ),
        )
    }

    fun snapshot(): TestTimingSnapshot =
        TestTimingSnapshot(
            availability = if (incomplete) TestTimingSnapshot.INCOMPLETE else TestTimingSnapshot.RECORDED,
            truncated = truncated,
            entries = entries.sortedWith(START_TIME_ORDER),
        )

    private fun remember(timing: TestMethodTiming) {
        if (entries.size < TestTimingSnapshot.MAX_ENTRIES) {
            entries.add(timing)
            return
        }
        truncated = true
        val dropAt = latestIndex()
        val latest = entries[dropAt]
        if (!startsBefore(timing, latest)) {
            return
        }
        entries[dropAt] = timing
    }

    private fun latestIndex(): Int {
        var index = 0
        for (candidate in 1 until entries.size) {
            if (startsAfter(entries[candidate], entries[index])) {
                index = candidate
            }
        }
        return index
    }

    private fun startsAfter(left: TestMethodTiming, right: TestMethodTiming): Boolean =
        left.startTimeEpochMs > right.startTimeEpochMs ||
            (left.startTimeEpochMs == right.startTimeEpochMs && left.endTimeEpochMs > right.endTimeEpochMs)

    private fun startsBefore(left: TestMethodTiming, right: TestMethodTiming): Boolean =
        left.startTimeEpochMs < right.startTimeEpochMs ||
            (left.startTimeEpochMs == right.startTimeEpochMs && left.endTimeEpochMs < right.endTimeEpochMs)
}

private val START_TIME_ORDER =
    compareBy<TestMethodTiming>({ it.startTimeEpochMs }, { it.endTimeEpochMs }, { it.displayName }, { it.methodName })

private fun resultName(result: TestOperationResult): String =
    when (result) {
        is TestFailureResult -> TestMethodTiming.RESULT_FAILURE
        is TestSkippedResult -> TestMethodTiming.RESULT_SKIPPED
        else -> TestMethodTiming.RESULT_SUCCESS
    }

/**
 * Gradle reports a test that was still running when the build was cancelled as
 * [TestSkippedResult]. The Tooling API result carries only start and end times, the
 * same type as `@Disabled` and assumption failures, so the skip reason is not available.
 *
 * Treat the skip as cancellation when this process already requested cancellation and
 * the operation clock straddles that moment (started before, finished at or after).
 * Skips that finished earlier stay, including `@Disabled` and assumptions. Skips that
 * start at or after the request stay too.
 */
internal fun isCancellationInterruptedSkip(
    result: TestOperationResult,
    cancellationRequestedAtMillis: Long?,
): Boolean {
    if (cancellationRequestedAtMillis == null || result !is TestSkippedResult) {
        return false
    }
    val startTime: Long
    val endTime: Long
    try {
        startTime = result.startTime
        endTime = result.endTime
    } catch (_: RuntimeException) {
        return false
    }
    return startTime < cancellationRequestedAtMillis && endTime >= cancellationRequestedAtMillis
}

internal fun taskPathFromDescriptor(descriptor: OperationDescriptor): String? {
    val seen = Collections.newSetFromMap(IdentityHashMap<OperationDescriptor, Boolean>(8))
    var current: OperationDescriptor? = descriptor
    var depth = 0
    while (current != null && depth < MAX_PARENT_DEPTH && seen.add(current)) {
        if (current is TaskOperationDescriptor) {
            val path = current.taskPath
            if (!path.isNullOrBlank()) {
                return path
            }
        }
        current = try {
            current.parent
        } catch (_: RuntimeException) {
            null
        }
        depth += 1
    }
    return null
}

private const val MAX_PARENT_DEPTH = 16
