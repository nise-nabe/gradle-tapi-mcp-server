package com.example.gradle.mcp.build

import com.example.gradle.mcp.support.problemProxy
import com.example.gradle.mcp.support.singleProblemEventProxy
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationSuccessResult
import org.gradle.tooling.events.problems.Problem
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
    fun `configureLauncher subscribes problem events only when enabled`() {
        val problemOperationType = OperationType.values()
            .firstOrNull { it.name == "PROBLEM" || it.name == "PROBLEMS" }
            ?: return

        captureOperationTypes(BuildProgressTracker(), includeProblems = false) shouldNotContain problemOperationType
        captureOperationTypes(BuildProgressTracker(), includeProblems = true) shouldContain problemOperationType
    }

    @Test
    fun `collects live problems from problem events`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val problem = problemProxy(
            displayName = "Deprecated API usage",
            details = "Task uses a deprecated input property",
            severity = Severity.WARNING,
            contextualLabel = "Task :compileJava",
        )

        listener.statusChanged(singleProblemEventProxy(problem))

        val snapshot = tracker.snapshot()
        snapshot.problems shouldHaveSize 0
        snapshot.liveProblems shouldHaveSize 1
        snapshot.liveProblems.single().label shouldBe "Deprecated API usage"
        snapshot.liveProblems.single().severity shouldBe "warning"
        snapshot.recentEvents.last().eventType shouldBe ProgressEventTypes.PROBLEM
    }

    @Test
    fun `tracks project configuration start and finish events`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val projectName = "Configure project :app"

        val configStart = Proxy.newProxyInstance(
            ProjectConfigurationStartEvent::class.java.classLoader,
            arrayOf(ProjectConfigurationStartEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> projectName
                    "getEventTime" -> 0L
                    else -> null
                }
            },
        ) as ProjectConfigurationStartEvent
        listener.statusChanged(configStart)

        var snapshot = tracker.snapshot()
        snapshot.runningTaskCount shouldBe 0
        snapshot.recentEvents.last().eventType shouldBe ProgressEventTypes.CONFIG_START
        snapshot.recentEvents.last().displayName shouldBe projectName
        snapshot.currentOperation shouldBe projectName

        val configFinish = Proxy.newProxyInstance(
            ProjectConfigurationFinishEvent::class.java.classLoader,
            arrayOf(ProjectConfigurationFinishEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> projectName
                    "getEventTime" -> 1L
                    "getResult" -> Proxy.newProxyInstance(
                        ProjectConfigurationSuccessResult::class.java.classLoader,
                        arrayOf(ProjectConfigurationSuccessResult::class.java),
                        InvocationHandler { _, _, _ -> null },
                    )
                    else -> null
                }
            },
        ) as ProjectConfigurationFinishEvent
        listener.statusChanged(configFinish)

        snapshot = tracker.snapshot()
        snapshot.runningTaskCount shouldBe 0
        snapshot.completedTaskCount shouldBe 0
        snapshot.recentEvents.last().eventType shouldBe ProgressEventTypes.CONFIG_FINISH
    }

    @Test
    fun `tracks project configuration failure events`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val projectName = "Configure project :broken"
        val failureMessage = "Could not compile build file"
        val problem = problemProxy(
            displayName = "Script compilation failed",
            details = "Unresolved reference: foo",
            severity = Severity.ERROR,
            contextualLabel = projectName,
        )
        val failure = failureProxy(problems = listOf(problem))

        val configFinish = Proxy.newProxyInstance(
            ProjectConfigurationFinishEvent::class.java.classLoader,
            arrayOf(ProjectConfigurationFinishEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> projectName
                    "getEventTime" -> 1L
                    "getResult" -> Proxy.newProxyInstance(
                        org.gradle.tooling.events.configuration.ProjectConfigurationFailureResult::class.java.classLoader,
                        arrayOf(org.gradle.tooling.events.configuration.ProjectConfigurationFailureResult::class.java),
                        InvocationHandler { _, method, _ ->
                            when (method.name) {
                                "getFailures" -> listOf(
                                    Proxy.newProxyInstance(
                                        org.gradle.tooling.Failure::class.java.classLoader,
                                        arrayOf(org.gradle.tooling.Failure::class.java),
                                        InvocationHandler { _, method, _ ->
                                            when (method.name) {
                                                "getMessage" -> failureMessage
                                                "getProblems" -> failure.problems
                                                else -> null
                                            }
                                        },
                                    ),
                                )
                                else -> null
                            }
                        },
                    )
                    else -> null
                }
            },
        ) as ProjectConfigurationFinishEvent
        listener.statusChanged(configFinish)

        val snapshot = tracker.snapshot()
        snapshot.runningTaskCount shouldBe 0
        snapshot.failedTaskCount shouldBe 0
        snapshot.recentEvents.last().eventType shouldBe ProgressEventTypes.CONFIG_FAIL
        snapshot.recentEvents.last().outcome shouldBe failureMessage
        snapshot.problems shouldHaveSize 1
        snapshot.problems.single().label shouldBe "Script compilation failed"
        snapshot.problems.single().details shouldBe "Unresolved reference: foo"
        snapshot.problems.single().severity shouldBe "error"
        snapshot.problems.single().contextualLabel shouldBe projectName
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

    private fun captureOperationTypes(
        tracker: BuildProgressTracker,
        includeProblems: Boolean = false,
    ): List<OperationType> {
        val captured = mutableListOf<OperationType>()
        lateinit var launcher: ConfigurableLauncher<*>
        launcher = Proxy.newProxyInstance(
            ConfigurableLauncher::class.java.classLoader,
            arrayOf(ConfigurableLauncher::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "addProgressListener" -> {
                        captured.clear()
                        args.orEmpty()
                            .drop(1)
                            .flatMap { argument ->
                                when (argument) {
                                    is Array<*> -> argument.filterIsInstance<OperationType>()
                                    is OperationType -> listOf(argument)
                                    else -> emptyList()
                                }
                            }
                            .toCollection(captured)
                        launcher
                    }
                    else -> launcher
                }
            },
        ) as ConfigurableLauncher<*>
        tracker.configureLauncher(launcher, includeProblems)
        return captured.toList()
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
}
