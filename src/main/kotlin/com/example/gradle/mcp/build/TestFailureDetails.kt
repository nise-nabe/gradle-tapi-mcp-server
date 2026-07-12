package com.example.gradle.mcp.build

import org.gradle.tooling.Failure

internal object TestFailureDetails {
    fun exceptionTypeFromFailure(failure: Failure?): String? {
        failure ?: return null
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
