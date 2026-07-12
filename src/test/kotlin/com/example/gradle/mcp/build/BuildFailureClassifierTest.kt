package com.example.gradle.mcp.build

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BuildFailureClassifierTest {
    @Test
    fun `test assertion failure suppresses Gradle distribution error`() {
        val stdout = """
            8 tests completed, 1 failed
            ArmeriaRouteNavigationSupportTest > testRelatedItemsWithKotlinVariableReference FAILED
                junit.framework.AssertionFailedError at ArmeriaRouteNavigationSupportTest.kt:208
            BUILD FAILED in 27s
        """.trimIndent()
        val progress = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_FAILED,
            currentOperation = null,
            completedTaskCount = 1,
            runningTaskCount = 0,
            failedTaskCount = 5,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = listOf(":plugin:test"),
            recentEvents = emptyList(),
            totalEventCount = 5,
            failedTests = listOf(
                FailedTestSnapshot(
                    className = "com.example.ArmeriaRouteNavigationSupportTest",
                    methodName = "testRelatedItemsWithKotlinVariableReference",
                    displayName = "testRelatedItemsWithKotlinVariableReference",
                ),
            ),
        )

        val classified = BuildFailureClassifier.classify(
            status = BuildProgressTracker.STATUS_FAILED,
            kind = "tests",
            error = "Could not execute tests using connection to Gradle distribution 'https://services.gradle.org/distributions/gradle-9.6.1-bin.zip'.",
            progress = progress,
            stdout = stdout,
        )

        classified.failureKind shouldBe FailureKind.TEST_FAILURE
        classified.error.shouldBeNull()
    }

    @Test
    fun `true connection failure keeps distribution error`() {
        val classified = BuildFailureClassifier.classify(
            status = BuildProgressTracker.STATUS_FAILED,
            kind = "tests",
            error = "Could not execute tests using connection to Gradle distribution 'https://services.gradle.org/distributions/gradle-9.6.1-bin.zip'.",
            progress = null,
            stdout = "",
        )

        classified.failureKind shouldBe FailureKind.CONNECTION_FAILURE
        classified.error shouldBe "Could not execute tests using connection to Gradle distribution 'https://services.gradle.org/distributions/gradle-9.6.1-bin.zip'."
    }

    @Test
    fun `task failure keeps error message`() {
        val classified = BuildFailureClassifier.classify(
            status = BuildProgressTracker.STATUS_FAILED,
            kind = "tasks",
            error = "Execution failed for task ':app:compileJava'.",
            progress = BuildProgressSnapshot(
                status = BuildProgressTracker.STATUS_FAILED,
                currentOperation = null,
                completedTaskCount = 0,
                runningTaskCount = 0,
                failedTaskCount = 1,
                completedTasks = emptyList(),
                runningTasks = emptyList(),
                failedTasks = listOf(":app:compileJava"),
                recentEvents = emptyList(),
                totalEventCount = 1,
            ),
            stdout = "> Task :app:compileJava FAILED\nBUILD FAILED in 1s",
        )

        classified.failureKind shouldBe FailureKind.TASK_FAILURE
        classified.error shouldBe "Execution failed for task ':app:compileJava'."
    }

    @Test
    fun `cancelled build reports CANCELLED`() {
        val classified = BuildFailureClassifier.classify(
            status = BuildProgressTracker.STATUS_CANCELLED,
            kind = "tasks",
            error = "Build cancelled",
            progress = null,
            stdout = "",
        )

        classified.failureKind shouldBe FailureKind.CANCELLED
        classified.error shouldBe "Build cancelled"
    }
}
