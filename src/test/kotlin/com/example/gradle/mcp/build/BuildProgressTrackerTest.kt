package com.example.gradle.mcp.build

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationSuccessResult
import org.gradle.tooling.events.problems.ContextualLabel
import org.gradle.tooling.events.problems.Details
import org.gradle.tooling.events.problems.Problem
import org.gradle.tooling.events.problems.ProblemDefinition
import org.gradle.tooling.events.problems.ProblemId
import org.gradle.tooling.events.problems.Severity
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestStartEvent
import org.gradle.tooling.events.test.TestSuccessResult
import org.gradle.tooling.events.download.FileDownloadFinishEvent
import org.gradle.tooling.events.download.FileDownloadOperationDescriptor
import org.gradle.tooling.events.download.FileDownloadResult
import org.gradle.tooling.events.download.FileDownloadStartEvent
import org.gradle.tooling.events.test.source.FilePosition
import org.gradle.tooling.events.test.source.FileSource
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
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
    fun `captures structured test metadata and failed test summaries`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()
        val displayName = "com.example.DemoTest.fails"
        val descriptor = jvmTestDescriptorProxy(
            displayName = displayName,
            className = "com.example.DemoTest",
            methodName = "fails",
            source = fileSourceProxy(
                file = File("src/test/kotlin/com/example/DemoTest.kt"),
                line = 42,
                column = 7,
            ),
        )

        listener.statusChanged(testStartEventProxy(displayName, descriptor))
        listener.statusChanged(
            testFinishEventProxy(
                displayName = displayName,
                descriptor = descriptor,
                result = testFailureResultProxy("expected:<1> but was:<2>"),
            ),
        )

        val snapshot = tracker.snapshot()
        val startDetails = snapshot.recentEvents.first { it.eventType == ProgressEventTypes.TEST_START }.testDetails.shouldNotBeNull()
        startDetails.className shouldBe "com.example.DemoTest"
        startDetails.methodName shouldBe "fails"
        startDetails.sourceType shouldBe "file"
        startDetails.sourcePath shouldBe "src/test/kotlin/com/example/DemoTest.kt"
        startDetails.sourceLine shouldBe 42
        startDetails.sourceColumn shouldBe 7

        val failureDetails = snapshot.recentEvents.last().testDetails.shouldNotBeNull()
        failureDetails.failureMessage shouldBe "expected:<1> but was:<2>"
        snapshot.failedTests.single().className shouldBe "com.example.DemoTest"
        snapshot.failedTests.single().methodName shouldBe "fails"
        snapshot.failedTests.single().failureMessage shouldBe "expected:<1> but was:<2>"
    }

    @Test
    fun `caps tracked failed tests after repeated failures`() {
        val tracker = BuildProgressTracker()
        val listener = tracker.asGradleListener()

        repeat(11) { index ->
            val displayName = "com.example.DemoTest.fails$index"
            val descriptor = jvmTestDescriptorProxy(
                displayName = displayName,
                className = "com.example.DemoTest",
                methodName = "fails$index",
                source = fileSourceProxy(
                    file = File("src/test/kotlin/com/example/DemoTest.kt"),
                    line = index,
                    column = null,
                ),
            )
            listener.statusChanged(testStartEventProxy(displayName, descriptor))
            listener.statusChanged(
                testFinishEventProxy(
                    displayName = displayName,
                    descriptor = descriptor,
                    result = testFailureResultProxy("failure $index"),
                ),
            )
        }

        tracker.snapshot().failedTests.size shouldBe FailedTestSnapshots.MAX_TRACKED_FAILED_TESTS
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

    @Test
    fun `configureLauncher subscribes file download events only when enabled`() {
        captureOperationTypes(trackDownloads = false) shouldNotContain OperationType.FILE_DOWNLOAD
        captureOperationTypes(trackDownloads = true) shouldContain OperationType.FILE_DOWNLOAD
    }

    @Test
    fun `tracks file download events when enabled`() {
        val tracker = BuildProgressTracker(trackDownloads = true)
        val listener = tracker.asGradleListener()
        val uri = URI.create("https://repo.example.com/libs/foo.jar")
        listener.statusChanged(fileDownloadStartEvent(uri, "Download foo"))
        tracker.snapshot().activeDownloadCount shouldBe 1
        listener.statusChanged(fileDownloadFinishEvent(uri, "Download foo", 1024L))
        val snapshot = tracker.snapshot()
        snapshot.activeDownloadCount shouldBe 0
        snapshot.recentDownloads.single().status shouldBe BuildProgressTracker.DOWNLOAD_STATUS_SUCCEEDED
    }

    @Test
    fun `download events do not overwrite currentOperation`() {
        val tracker = BuildProgressTracker(trackDownloads = true)
        tracker.markStarting("Gradle tasks: build")
        val listener = tracker.asGradleListener()
        val uri = URI.create("https://repo.example.com/libs/foo.jar")
        listener.statusChanged(fileDownloadStartEvent(uri, "Download foo"))
        listener.statusChanged(fileDownloadFinishEvent(uri, "Download foo", 1024L))

        tracker.snapshot().currentOperation shouldBe "Gradle tasks: build"
    }

    private fun captureOperationTypes(trackDownloads: Boolean): List<OperationType> {
        val tracker = BuildProgressTracker(trackDownloads = trackDownloads)
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
        tracker.configureLauncher(launcher)
        return captured.toList()
    }

    private fun fileDownloadDescriptor(uri: URI): FileDownloadOperationDescriptor =
        Proxy.newProxyInstance(
            FileDownloadOperationDescriptor::class.java.classLoader,
            arrayOf(FileDownloadOperationDescriptor::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getUri" -> uri
                    "getName", "getDisplayName" -> uri.toString()
                    "getParent" -> null
                    else -> null
                }
            },
        ) as FileDownloadOperationDescriptor

    private fun fileDownloadStartEvent(uri: URI, displayName: String): FileDownloadStartEvent =
        Proxy.newProxyInstance(
            FileDownloadStartEvent::class.java.classLoader,
            arrayOf(FileDownloadStartEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> displayName
                    "getEventTime" -> 0L
                    "getDescriptor" -> fileDownloadDescriptor(uri)
                    else -> null
                }
            },
        ) as FileDownloadStartEvent

    private fun fileDownloadFinishEvent(uri: URI, displayName: String, bytes: Long): FileDownloadFinishEvent =
        Proxy.newProxyInstance(
            FileDownloadFinishEvent::class.java.classLoader,
            arrayOf(FileDownloadFinishEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> displayName
                    "getEventTime" -> 1L
                    "getDescriptor" -> fileDownloadDescriptor(uri)
                    "getResult" -> Proxy.newProxyInstance(
                        FileDownloadResult::class.java.classLoader,
                        arrayOf(FileDownloadResult::class.java),
                        InvocationHandler { _, m, _ ->
                            when (m.name) {
                                "getBytesDownloaded" -> bytes
                                "getStartTime", "getEndTime" -> 0L
                                else -> null
                            }
                        },
                    )
                    else -> null
                }
            },
        ) as FileDownloadFinishEvent

    private fun testStartEventProxy(
        displayName: String,
        descriptor: JvmTestOperationDescriptor,
    ): TestStartEvent =
        Proxy.newProxyInstance(
            TestStartEvent::class.java.classLoader,
            arrayOf(TestStartEvent::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDisplayName" -> displayName
                    "getEventTime" -> 0L
                    "getDescriptor" -> descriptor
                    else -> null
                }
            },
        ) as TestStartEvent

    private fun testFinishEventProxy(
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

    private fun jvmTestDescriptorProxy(
        displayName: String,
        className: String,
        methodName: String,
        source: FileSource,
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
                    "getSource" -> source
                    else -> null
                }
            },
        ) as JvmTestOperationDescriptor

    private fun fileSourceProxy(
        file: File,
        line: Int,
        column: Int?,
    ): FileSource =
        Proxy.newProxyInstance(
            FileSource::class.java.classLoader,
            arrayOf(FileSource::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getFile" -> file
                    "getPosition" -> filePositionProxy(line, column)
                    else -> null
                }
            },
        ) as FileSource

    private fun filePositionProxy(line: Int, column: Int?): FilePosition =
        Proxy.newProxyInstance(
            FilePosition::class.java.classLoader,
            arrayOf(FilePosition::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getLine" -> line
                    "getColumn" -> column
                    else -> null
                }
            },
        ) as FilePosition

    private fun testFailureResultProxy(message: String): TestFailureResult =
        Proxy.newProxyInstance(
            TestFailureResult::class.java.classLoader,
            arrayOf(TestFailureResult::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getFailures" -> listOf(
                        Proxy.newProxyInstance(
                            Failure::class.java.classLoader,
                            arrayOf(Failure::class.java),
                            InvocationHandler { _, method, _ ->
                                when (method.name) {
                                    "getMessage" -> message
                                    "getDescription" -> null
                                    "getProblems", "getCauses" -> emptyList<Any>()
                                    else -> null
                                }
                            },
                        ) as Failure,
                    )
                    "getStartTime", "getEndTime" -> 0L
                    else -> null
                }
            },
        ) as TestFailureResult

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
