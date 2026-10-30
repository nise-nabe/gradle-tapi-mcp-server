package com.example.gradle.mcp.build

import com.example.gradle.mcp.build.persistence.BuildRecordStore
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.terminalFailureFields
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import com.example.gradle.mcp.support.throwingOnTestLauncherProjectConnection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.tooling.BuildException
import org.gradle.tooling.Failure
import org.gradle.tooling.TestAssertionFailure
import org.gradle.tooling.TestExecutionException
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestSuccessResult
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class TestRetryOutcomeTest {
    @Test
    fun `passed retry is flaky and does not fail the build`() {
        val tracker = runningTracker("Gradle tests: FlakyTest")
        val listener = tracker.asGradleListener()
        listener.recordAttempt(
            className = "com.example.FlakyTest",
            methodName = "failsThenPasses()",
            failed = true,
            message = "first attempt",
            startTime = 10L,
        )
        listener.recordContainerFailure("Test class com.example.FlakyTest failed", "com.example.FlakyTest")
        listener.recordContainerFailure("Gradle Test Executor 1 failed", className = null)
        listener.recordAttempt(
            className = "com.example.FlakyTest",
            methodName = "failsThenPasses()",
            failed = false,
            message = null,
            startTime = 20L,
        )
        listener.recordContainerFailure("Gradle Test Run :test failed", className = null)

        val snapshot = tracker.snapshot()
        snapshot.failedTests shouldBe emptyList()
        snapshot.failedTestCount shouldBe 0
        snapshot.flakyTests.map { it.methodName } shouldContainExactly listOf("failsThenPasses()")
        snapshot.flakyTests.single().failureMessage shouldBe "first attempt"
        snapshot.failedGradleTaskCount shouldBe 0
        snapshot.testTimings.entries.map { it.result } shouldContainExactly listOf(
            TestMethodTiming.RESULT_FAILURE,
            TestMethodTiming.RESULT_SUCCESS,
        )

        val record = finishTests(
            tracker,
            TestExecutionException(
                "Could not execute tests using connection to Gradle distribution 'https://example'.",
                RuntimeException(
                    "Test failed.\n    Failed tests:\n        Test com.example.FlakyTest#failsThenPasses() (Task: :test)",
                ),
            ),
        )

        record.progressTracker.snapshot().status shouldBe BuildProgressTracker.STATUS_SUCCEEDED
        record.failureKind.shouldBeNull()
        record.errorMessage.shouldBeNull()
        val progress = BuildStatusView.fromRecord(record).progress
        progress.shouldNotBeNull()
        val fields = terminalFailureFields(progress, ProgressResponseOptions())
        fields["flakyTestCount"] shouldBe 1
        fields.containsKey("testFailures") shouldBe false
        val summary = BuildStatusView.fromRecord(record).buildSummary
        summary?.get("resultLine") shouldBe null
        summary?.get("taskSummaryLine") shouldBe "2 actionable tasks: 1 executed"
    }

    @Test
    fun `genuine test failure stays failed with one final entry`() {
        val tracker = runningTracker("Gradle tests: AlwaysFailTest")
        val listener = tracker.asGradleListener()
        listener.recordAttempt(
            className = "com.example.AlwaysFailTest",
            methodName = "alwaysFails()",
            failed = true,
            message = "genuine failure",
            startTime = 10L,
        )
        listener.recordContainerFailure("Test class com.example.AlwaysFailTest failed", "com.example.AlwaysFailTest")
        listener.recordAttempt(
            className = "com.example.AlwaysFailTest",
            methodName = "alwaysFails()",
            failed = true,
            message = "genuine failure",
            startTime = 30L,
        )
        listener.statusChanged(taskFailure("Task :test failed"))

        val before = tracker.snapshot()
        before.failedTests.map { it.methodName } shouldContainExactly listOf("alwaysFails()")
        before.failedTestCount shouldBe 1
        before.flakyTests shouldBe emptyList()
        before.failedGradleTaskCount shouldBe 1
        before.testTimings.entries.map { it.result } shouldContainExactly listOf(
            TestMethodTiming.RESULT_FAILURE,
            TestMethodTiming.RESULT_FAILURE,
        )

        val record = finishTests(
            tracker,
            BuildException(
                "Could not execute tests using connection to Gradle distribution 'https://example'.",
                RuntimeException("Execution failed for task ':test'."),
            ),
        )

        record.progressTracker.snapshot().status shouldBe BuildProgressTracker.STATUS_FAILED
        record.failureKind shouldBe FailureKind.TEST_FAILURE
        val fields = terminalFailureFields(
            record.progressTracker.snapshot().copy(status = BuildProgressTracker.STATUS_FAILED),
            ProgressResponseOptions(),
        )
        (fields["testFailures"] as List<*>).size shouldBe 1
        fields["failedTestCount"] shouldBe 1
        fields.containsKey("flakyTests") shouldBe false
    }

    @Test
    fun `cancellation still wins over a passed retry`() {
        val tracker = runningTracker("Gradle tests: FlakyTest")
        tracker.asGradleListener().recordAttempt(
            className = "com.example.FlakyTest",
            methodName = "failsThenPasses()",
            failed = true,
            message = "first attempt",
            startTime = 10L,
        )
        tracker.asGradleListener().recordAttempt(
            className = "com.example.FlakyTest",
            methodName = "failsThenPasses()",
            failed = false,
            message = null,
            startTime = 20L,
        )
        val record = testBuildRecord(
            id = "retry-cancelled",
            kind = BuildKind.TESTS,
            selection = TestRunSelection.Classes(listOf("com.example.FlakyTest")),
            tracker = tracker,
            projectDirectory = testProjectDirectory.absolutePath,
        )
        record.requestCancellation()

        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            throwingOnTestLauncherProjectConnection(
                TestExecutionException(
                    "Could not execute tests using connection to Gradle distribution 'https://example'.",
                    RuntimeException("Test failed.\n    Failed tests:\n        Test com.example.FlakyTest#failsThenPasses()"),
                ),
            ),
            testProjectDirectory,
        )
        BuildRunner(connectionManager, BuildRegistry(), BuildRecordStore()).runBuild(
            record,
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Classes(listOf("com.example.FlakyTest")),
                testScopeValidatedAtPreflight = true,
            ),
            BuildProgressNotifier(null),
        )

        record.progressTracker.snapshot().status shouldBe BuildProgressTracker.STATUS_CANCELLED
        record.failureKind shouldBe FailureKind.CANCELLED
    }

    @Test
    fun `no matching tests stays failed`() {
        val record = finishTests(
            runningTracker("Gradle tests: MissingTest"),
            RuntimeException("No matching tests found in any candidate test task."),
        )

        record.progressTracker.snapshot().status shouldBe BuildProgressTracker.STATUS_FAILED
        record.failureKind shouldBe FailureKind.TASK_FAILURE
    }

    private fun finishTests(tracker: BuildProgressTracker, failure: Throwable): BuildRecord {
        val streams = CapturingStreams().also {
            it.appendStdoutForTests("> Task :test\n2 actionable tasks: 1 executed\n")
            it.appendStderrForTests("FAILURE: Build failed with an exception.\n\nBUILD FAILED in 4s\n")
        }
        val record = testBuildRecord(
            id = "retry-outcome",
            kind = BuildKind.TESTS,
            selection = TestRunSelection.Classes(listOf("com.example.FlakyTest")),
            tracker = tracker,
            streams = streams,
            projectDirectory = testProjectDirectory.absolutePath,
        )
        val connectionManager = GradleConnectionManager()
        connectionManager.seedConnectionForTests(
            throwingOnTestLauncherProjectConnection(failure),
            testProjectDirectory,
        )
        BuildRunner(connectionManager, BuildRegistry(), BuildRecordStore()).runBuild(
            record,
            BuildRunRequest(
                projectDirectory = testProjectDirectory,
                kind = BuildKind.TESTS,
                selection = TestRunSelection.Classes(listOf("com.example.FlakyTest")),
                testScopeValidatedAtPreflight = true,
            ),
            BuildProgressNotifier(null),
        )
        return record
    }
}

private fun ProgressListener.recordAttempt(
    className: String,
    methodName: String,
    failed: Boolean,
    message: String?,
    startTime: Long,
) {
    val displayName = if (failed) {
        "Test $methodName($className) failed"
    } else {
        "Test $methodName($className) succeeded"
    }
    val descriptor = jvmDescriptor(displayName, className, methodName)
    val result = if (failed) {
        assertionFailure(message ?: "failed", startTime)
    } else {
        successResult(startTime)
    }
    statusChanged(testFinish(displayName, descriptor, result))
}

private fun ProgressListener.recordContainerFailure(displayName: String, className: String?) {
    val descriptor = jvmDescriptor(displayName, className, methodName = null)
    statusChanged(testFinish(displayName, descriptor, emptyFailure()))
}

private fun jvmDescriptor(
    displayName: String,
    className: String?,
    methodName: String?,
): JvmTestOperationDescriptor =
    Proxy.newProxyInstance(
        JvmTestOperationDescriptor::class.java.classLoader,
        arrayOf(JvmTestOperationDescriptor::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDisplayName", "getName", "getTestDisplayName" -> displayName
                "getParent" -> null
                "getClassName" -> className
                "getMethodName" -> methodName
                "getSource" -> null
                else -> null
            }
        },
    ) as JvmTestOperationDescriptor

private fun testFinish(
    displayName: String,
    descriptor: JvmTestOperationDescriptor,
    result: Any,
): TestFinishEvent =
    Proxy.newProxyInstance(
        TestFinishEvent::class.java.classLoader,
        arrayOf(TestFinishEvent::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDisplayName" -> displayName
                "getEventTime" -> 1L
                "getDescriptor" -> descriptor
                "getResult" -> result
                else -> null
            }
        },
    ) as TestFinishEvent

private fun assertionFailure(message: String, startTime: Long): TestFailureResult =
    Proxy.newProxyInstance(
        TestFailureResult::class.java.classLoader,
        arrayOf(TestFailureResult::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getFailures" -> listOf(assertion(message))
                "getStartTime" -> startTime
                "getEndTime" -> startTime + 5L
                else -> null
            }
        },
    ) as TestFailureResult

private fun emptyFailure(): TestFailureResult =
    Proxy.newProxyInstance(
        TestFailureResult::class.java.classLoader,
        arrayOf(TestFailureResult::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getFailures" -> emptyList<Failure>()
                "getStartTime", "getEndTime" -> 0L
                else -> null
            }
        },
    ) as TestFailureResult

private fun successResult(startTime: Long): TestSuccessResult =
    Proxy.newProxyInstance(
        TestSuccessResult::class.java.classLoader,
        arrayOf(TestSuccessResult::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getStartTime" -> startTime
                "getEndTime" -> startTime + 5L
                else -> null
            }
        },
    ) as TestSuccessResult

private fun assertion(message: String): Failure =
    Proxy.newProxyInstance(
        Failure::class.java.classLoader,
        arrayOf(TestAssertionFailure::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getMessage" -> message
                "getDescription" -> "org.opentest4j.AssertionFailedError: $message"
                "getClassName" -> "org.opentest4j.AssertionFailedError"
                "getProblems", "getCauses" -> emptyList<Any>()
                else -> null
            }
        },
    ) as Failure

private fun taskFailure(displayName: String): TaskFinishEvent =
    Proxy.newProxyInstance(
        TaskFinishEvent::class.java.classLoader,
        arrayOf(TaskFinishEvent::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDisplayName" -> displayName
                "getEventTime" -> 1L
                "getResult" -> Proxy.newProxyInstance(
                    TaskFailureResult::class.java.classLoader,
                    arrayOf(TaskFailureResult::class.java),
                    InvocationHandler { _, resultMethod, _ ->
                        when (resultMethod.name) {
                            "getFailures" -> listOf(assertion("There were failing tests."))
                            "getStartTime", "getEndTime" -> 0L
                            "isIncremental" -> false
                            else -> null
                        }
                    },
                )
                else -> null
            }
        },
    ) as TaskFinishEvent
