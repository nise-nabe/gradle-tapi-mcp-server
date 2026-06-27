package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProblemSnapshot
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.problems.Problem
import org.gradle.tooling.events.problems.Severity

internal object ProblemsSerializer {
    fun fromFailureResult(result: FailureResult): List<BuildProblemSnapshot> =
        result.failures.flatMap { failure -> fromFailure(failure) }

    fun fromFailure(failure: Failure): List<BuildProblemSnapshot> {
        val collected = mutableListOf<BuildProblemSnapshot>()
        collectProblems(failure, collected)
        return collected
    }

    fun mergeDistinct(
        target: MutableList<BuildProblemSnapshot>,
        additions: List<BuildProblemSnapshot>,
    ) {
        val indexByKey = target.withIndex()
            .associate { (index, problem) -> problem.dedupeKey() to index }
            .toMutableMap()
        additions.forEach { problem ->
            val key = problem.dedupeKey()
            val existingIndex = indexByKey[key]
            if (existingIndex == null) {
                indexByKey[key] = target.size
                target.add(problem)
            } else {
                val existing = target[existingIndex]
                val mergedSolutions = (existing.solutions + problem.solutions).distinct()
                if (mergedSolutions != existing.solutions) {
                    target[existingIndex] = existing.copy(solutions = mergedSolutions)
                }
            }
        }
    }

    fun toResponseMaps(problems: List<BuildProblemSnapshot>): List<Map<String, Any?>> =
        problems.map { problem ->
            buildMap {
                put("label", problem.label)
                problem.details?.let { put("details", it) }
                problem.severity?.let { put("severity", it) }
                if (problem.solutions.isNotEmpty()) {
                    put("solutions", problem.solutions)
                }
                problem.contextualLabel?.let { put("contextualLabel", it) }
            }
        }

    private fun collectProblems(failure: Failure, into: MutableList<BuildProblemSnapshot>) {
        runCatching {
            failure.problems.orEmpty().forEach { problem ->
                into.add(toSnapshot(problem))
            }
        }
        runCatching {
            failure.causes.orEmpty().forEach { cause ->
                collectProblems(cause, into)
            }
        }
    }

    private fun toSnapshot(problem: Problem): BuildProblemSnapshot {
        val definition = problem.definition
        val label = definition.id.displayName.takeIf { it.isNotBlank() }
            ?: definition.id.name
        return BuildProblemSnapshot(
            label = label,
            details = problem.details?.details?.takeIf { it.isNotBlank() },
            severity = severityName(definition.severity),
            solutions = problem.solutions.mapNotNull { it.solution.takeIf(String::isNotBlank) },
            contextualLabel = problem.contextualLabel?.contextualLabel?.takeIf { it.isNotBlank() },
        )
    }

    private fun severityName(severity: Severity): String? =
        when {
            severity === Severity.ERROR -> "error"
            severity === Severity.WARNING -> "warning"
            severity === Severity.ADVICE -> "advice"
            !severity.isKnown -> "unknown"
            else -> null
        }
}
