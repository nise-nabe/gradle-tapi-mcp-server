package com.example.gradle.mcp.build

import org.gradle.tooling.Failure
import org.gradle.tooling.TestAssertionFailure
import org.gradle.tooling.TestFailure
import org.gradle.tooling.TestFrameworkFailure

internal object TestFailureDetails {
    const val FAILURE_TYPE_ASSERTION = "assertion"
    const val FAILURE_TYPE_FRAMEWORK = "framework"

    fun failureTypeFromFailure(failure: Failure?): String? =
        when (failure) {
            is TestFrameworkFailure -> FAILURE_TYPE_FRAMEWORK
            is TestAssertionFailure -> FAILURE_TYPE_ASSERTION
            else -> null
        }

    /**
     * Classifies a persisted exception type the way [failureTypeFromFailure] classifies a
     * Tooling API failure. Gradle maps `AssertionError` and the assertion subclasses it
     * recognizes to [org.gradle.tooling.TestAssertionFailure]; every other test exception
     * becomes [org.gradle.tooling.TestFrameworkFailure].
     *
     * Old `events.ndjson` rows have `exceptionType` and no `failureType`, and the class
     * itself is not on this process's classpath, so the check uses the simple name.
     */
    fun failureTypeFromExceptionType(exceptionType: String?): String? {
        val simpleName = exceptionType?.trim()?.takeIf { it.isNotEmpty() }?.substringAfterLast('.')
            ?: return null
        return if (isAssertionExceptionName(simpleName)) {
            FAILURE_TYPE_ASSERTION
        } else {
            FAILURE_TYPE_FRAMEWORK
        }
    }

    private fun isAssertionExceptionName(simpleName: String): Boolean =
        simpleName.endsWith("AssertionError") ||
            simpleName.endsWith("AssertionFailedError") ||
            simpleName.endsWith("ComparisonFailure") ||
            simpleName == "MultipleFailuresError" ||
            simpleName == "MultipleAssertionsError"

    fun exceptionTypeFromFailure(failure: Failure?): String? {
        failure ?: return null
        (failure as? TestFailure)?.className?.takeIf { it.isNotBlank() }?.let { return it }
        val description = failure.description
        if (!description.isNullOrBlank()) {
            val prefix = description.substringBefore(':').trim()
            if (prefix.contains('.')) {
                return prefix
            }
        }
        return null
    }
}
