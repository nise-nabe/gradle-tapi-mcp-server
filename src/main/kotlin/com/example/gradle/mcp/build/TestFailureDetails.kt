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
