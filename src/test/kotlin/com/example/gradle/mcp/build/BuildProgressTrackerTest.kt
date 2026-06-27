package com.example.gradle.mcp.build

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.problems.ContextualLabel
import org.gradle.tooling.events.problems.Details
import org.gradle.tooling.events.problems.Problem
import org.gradle.tooling.events.problems.ProblemDefinition
import org.gradle.tooling.events.problems.ProblemId
import org.gradle.tooling.events.problems.Severity
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestStartEvent
import org.gradle.tooling.events.test.TestSuccessResult
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class BuildProgressTrackerTest {
    @Test
    fun `markCancelled does not overwrite succeeded status`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markSucceeded()

        tracker.markCancelled("late cancellation")

        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_SUCCEEDED
    }

    @Test
    fun `markFailed does not overwrite cancelled status`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markCancelled("Build cancelled")

        tracker.markFailed("late failure")

        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_CANCELLED
    }

    @Test
    fun `markFailed does not overwrite succeeded status`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markSucceeded()

        tracker.markFailed("late failure")

        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_SUCCEEDED
    }

    @Test
    fun `markSucceeded does not overwrite failed status`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("Gradle tasks: build")
        tracker.markFailed("Gradle connection closed")

        tracker.markSucceeded()

        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_FAILED
    }

    @Test
    fun `terminal transitions do not emit duplicate update callbacks`() {
        var notifyCount = 0
        val tracker = BuildProgressTracker(onUpdate = { notifyCount++ })
        tracker.markStarting("Gradle tasks: build")
        val afterStart = notifyCount
        tracker.markSucceeded()
        val afterSuccess = notifyCount

        tracker.markFailed("late failure")
        notifyCount shouldBe afterSuccess
        afterStart shouldBeGreaterThan 0
        afterSuccess shouldBeGreaterThan afterStart

        tracker.markSucceeded()
        notifyCount shouldBe afterSuccess
    }

    @Test
    fun `tracks lifecycle status transitions`() {
        val tracker = BuildProgressTracker()

        tracker.markStarting("Gradle tasks: build")
        tracker.snapshot().status shouldBe BuildProgressTracker.STATUS_RUNNING

        tracker.markSucceeded()
        val snapshot = tracker.snapshot()
        snapshot.status shouldBe BuildProgressTracker.STATUS_SUCCEEDED
        snapshot.runningTaskCount shouldBe 0
    }

    @Test
    fun `notifies only when new progress events arrive`() {
        val tracker = BuildProgressTracker()
        tracker.markStarting("build")

        tracker.shouldNotifyProgress().shouldBeTrue()
        tracker.shouldNotifyProgress().shouldBeFalse()

        tracker.markSucceeded()
        tracker.shouldNotifyProgress().shouldBeTrue()
    }

    @Test
    fun `tracks test start and finish events`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val testName = "com.example.DemoTest"

        val testStart = Proxy.newProxyInstance(
            TestStartEvent::class.java.classLoader,
            arrayOf(TestStartEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> testName
                    "getEventTime" -> 0L
                    else -> null
                }
            },
        ) as TestStartEvent
        listener.statusChanged(testStart)

        var snapshot = tracker.snapshot()
        snapshot.runningTaskCount shouldBe 1
        snapshot.runningTasks.single() shouldBe testName

        val testFinish = Proxy.newProxyInstance(
            TestFinishEvent::class.java.classLoader,
            arrayOf(TestFinishEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> testName
                    "getEventTime" -> 1L
                    "getResult" -> Proxy.newProxyInstance(
                        TestSuccessResult::class.java.classLoader,
                        arrayOf(TestSuccessResult::class.java),
                        InvocationHandler { _, _, _ -> null },
                    )
                    else -> null
                }
            },
        ) as TestFinishEvent
        listener.statusChanged(testFinish)

        snapshot = tracker.snapshot()
        snapshot.runningTaskCount shouldBe 0
        snapshot.completedTaskCount shouldBe 1
        snapshot.recentEvents.last().eventType shouldBe "TEST_SUCCESS"
    }

    @Test
    fun `collects structured problems from root failure result`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val displayName = "Run build"
        val problem = problemProxy(
            displayName = "Compilation failed",
            details = "cannot find symbol",
            severity = Severity.ERROR,
            contextualLabel = "Task :compileJava",
        )
        val failure = failureProxy(problems = listOf(problem))
        val rootFinish = Proxy.newProxyInstance(
            FinishEvent::class.java.classLoader,
            arrayOf(FinishEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> displayName
                    "getEventTime" -> 1L
                    "getResult" -> failureResultProxy(listOf(failure))
                    else -> null
                }
            },
        ) as FinishEvent

        listener.statusChanged(rootFinish)

        val snapshot = tracker.snapshot()
        snapshot.problems shouldHaveSize 1
        snapshot.problems.single().label shouldBe "Compilation failed"
        snapshot.problems.single().details shouldBe "cannot find symbol"
        snapshot.problems.single().severity shouldBe "error"
        snapshot.problems.single().contextualLabel shouldBe "Task :compileJava"
        snapshot.recentEvents.last().eventType shouldBe ProgressEventTypes.ROOT_FINISH
    }

    @Test
    fun `keeps only the most recent events`() {
        val tracker = BuildProgressTracker()
        repeat(40) { index ->
            tracker.markStarting("step-$index")
        }

        val snapshot = tracker.snapshot()
        snapshot.recentEvents.size shouldBeLessThanOrEqual 30
        snapshot.totalEventCount shouldBe 40
    }

    private fun failureResultProxy(failures: List<Failure>): FailureResult =
        Proxy.newProxyInstance(
            FailureResult::class.java.classLoader,
            arrayOf(FailureResult::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getFailures" -> failures
                    "getStartTime", "getEndTime" -> 0L
                    else -> null
                }
            },
        ) as FailureResult

    private fun failureProxy(
        problems: List<Problem>,
        causes: List<Failure> = emptyList(),
    ): Failure =
        Proxy.newProxyInstance(
            Failure::class.java.classLoader,
            arrayOf(Failure::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getMessage" -> "Build failed"
                    "getDescription" -> null
                    "getProblems" -> problems
                    "getCauses" -> causes
                    else -> null
                }
            },
        ) as Failure

    private fun problemProxy(
        displayName: String,
        details: String?,
        severity: Severity,
        contextualLabel: String? = null,
    ): Problem {
        val problemId = Proxy.newProxyInstance(
            ProblemId::class.java.classLoader,
            arrayOf(ProblemId::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> displayName
                    "getName" -> "compilation-failed"
                    "getGroup" -> null
                    else -> null
                }
            },
        ) as ProblemId
        val definition = Proxy.newProxyInstance(
            ProblemDefinition::class.java.classLoader,
            arrayOf(ProblemDefinition::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getId" -> problemId
                    "getSeverity" -> severity
                    "getDocumentationLink" -> null
                    else -> null
                }
            },
        ) as ProblemDefinition
        return Proxy.newProxyInstance(
            Problem::class.java.classLoader,
            arrayOf(Problem::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDefinition" -> definition
                    "getDetails" -> details?.let { text ->
                        Proxy.newProxyInstance(
                            Details::class.java.classLoader,
                            arrayOf(Details::class.java),
                            InvocationHandler { _, method, _ ->
                                when (method.name) {
                                    "getDetails" -> text
                                    else -> null
                                }
                            },
                        ) as Details
                    }
                    "getContextualLabel" -> contextualLabel?.let { text ->
                        Proxy.newProxyInstance(
                            ContextualLabel::class.java.classLoader,
                            arrayOf(ContextualLabel::class.java),
                            InvocationHandler { _, method, _ ->
                                when (method.name) {
                                    "getContextualLabel" -> text
                                    else -> null
                                }
                            },
                        ) as ContextualLabel
                    }
                    "getSolutions" -> emptyList<Any>()
                    "getOriginLocations", "getContextualLocations", "getFailure", "getAdditionalData" -> emptyList<Any>()
                    else -> null
                }
            },
        ) as Problem
    }
}
