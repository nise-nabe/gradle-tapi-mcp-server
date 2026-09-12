package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProblemSnapshot
import com.example.gradle.mcp.build.ProblemLocationSnapshot
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.problems.FileLocation
import org.gradle.tooling.events.problems.LineInFileLocation
import org.gradle.tooling.events.problems.Location
import org.gradle.tooling.events.problems.Problem
import org.gradle.tooling.events.problems.ProblemAggregation
import org.gradle.tooling.events.problems.ProblemAggregationEvent
import org.gradle.tooling.events.problems.ProblemContext
import org.gradle.tooling.events.problems.ProblemDefinition
import org.gradle.tooling.events.problems.ProblemEvent
import org.gradle.tooling.events.problems.ProblemId
import org.gradle.tooling.events.problems.ProblemSummariesEvent
import org.gradle.tooling.events.problems.ProblemSummary
import org.gradle.tooling.events.problems.Severity
import org.gradle.tooling.events.problems.SingleProblemEvent

internal object ProblemsSerializer {
    fun fromFailureResult(result: FailureResult): List<BuildProblemSnapshot> =
        result.failures.flatMap { failure -> fromFailure(failure) }

    fun fromProblemEvent(event: ProblemEvent): List<BuildProblemSnapshot> =
        when (event) {
            is SingleProblemEvent -> listOf(fromProblem(event.problem))
            is ProblemAggregationEvent -> fromProblemAggregation(event.problemAggregation)
            is ProblemSummariesEvent -> fromProblemSummaries(event.problemSummaries)
            else -> emptyList()
        }

    fun fromFailure(failure: Failure): List<BuildProblemSnapshot> {
        val collected = mutableListOf<BuildProblemSnapshot>()
        collectProblems(failure, collected)
        return collected
    }

    fun fromProblem(problem: Problem): BuildProblemSnapshot = toSnapshot(problem)

    fun mergeDistinct(
        target: MutableList<BuildProblemSnapshot>,
        additions: List<BuildProblemSnapshot>,
    ): Boolean {
        if (additions.isEmpty()) {
            return false
        }
        var changed = false
        val indexByKey = target.withIndex()
            .associate { (index, problem) -> problem.dedupeKey() to index }
            .toMutableMap()
        additions.forEach { problem ->
            val key = problem.dedupeKey()
            val existingIndex = indexByKey[key]
            if (existingIndex == null) {
                indexByKey[key] = target.size
                target.add(problem)
                changed = true
            } else {
                val existing = target[existingIndex]
                val mergedSolutions = (existing.solutions + problem.solutions).distinct()
                if (mergedSolutions != existing.solutions) {
                    target[existingIndex] = existing.copy(solutions = mergedSolutions)
                    changed = true
                }
            }
        }
        return changed
    }

    fun mergedDistinct(
        initial: List<BuildProblemSnapshot>,
        additions: List<BuildProblemSnapshot>,
    ): List<BuildProblemSnapshot> =
        initial.toMutableList().also { merged ->
            mergeDistinct(merged, additions)
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
                locationResponse(problem.originLocations)?.let { put("originLocations", it) }
                locationResponse(problem.contextualLocations)?.let { put("contextualLocations", it) }
            }
        }

    private fun collectProblems(failure: Failure, into: MutableList<BuildProblemSnapshot>) {
        runCatching {
            failure.problems.orEmpty().forEach { problem ->
                into.add(fromProblem(problem))
            }
        }
        runCatching {
            failure.causes.orEmpty().forEach { cause ->
                collectProblems(cause, into)
            }
        }
    }

    private fun fromProblemAggregation(aggregation: ProblemAggregation): List<BuildProblemSnapshot> {
        val contexts = aggregation.problemContext
        if (contexts.isEmpty()) {
            return listOf(toSnapshot(aggregation.definition))
        }
        return contexts.map { context ->
            toSnapshot(aggregation.definition, context)
        }
    }

    private fun fromProblemSummaries(summaries: List<ProblemSummary>): List<BuildProblemSnapshot> =
        summaries.map { summary ->
            BuildProblemSnapshot(
                label = problemLabel(summary.problemId),
                details = summary.count?.let { count -> "Occurred $count times" },
            )
        }

    private fun toSnapshot(problem: Problem): BuildProblemSnapshot {
        val definition = problem.definition
        return BuildProblemSnapshot(
            label = problemLabel(definition.id),
            details = problem.details?.details?.takeIf { it.isNotBlank() },
            severity = severityName(definition.severity),
            solutions = problem.solutions.mapNotNull { it.solution.takeIf(String::isNotBlank) },
            contextualLabel = problem.contextualLabel?.contextualLabel?.takeIf { it.isNotBlank() },
            originLocations = safeLocations { problem.originLocations },
            contextualLocations = safeLocations { problem.contextualLocations },
        )
    }

    private fun toSnapshot(definition: ProblemDefinition): BuildProblemSnapshot =
        BuildProblemSnapshot(
            label = problemLabel(definition.id),
            severity = severityName(definition.severity),
        )

    private fun toSnapshot(
        definition: ProblemDefinition,
        context: ProblemContext,
    ): BuildProblemSnapshot =
        BuildProblemSnapshot(
            label = problemLabel(definition.id),
            details = context.details?.details?.takeIf { it.isNotBlank() }
                ?: context.failure?.message?.takeIf { it.isNotBlank() },
            severity = severityName(definition.severity),
            solutions = context.solutions.mapNotNull { it.solution.takeIf(String::isNotBlank) },
            originLocations = safeLocations { context.originLocations },
            contextualLocations = safeLocations { context.contextualLocations },
        )

    private fun safeLocations(getter: () -> Collection<Location>?): List<ProblemLocationSnapshot> =
        runCatching { locationSnapshots(getter()) }.getOrDefault(emptyList())

    private fun locationSnapshots(locations: Collection<Location>?): List<ProblemLocationSnapshot> =
        locations.orEmpty().mapNotNull { location -> toLocationSnapshot(location) }

    private fun toLocationSnapshot(location: Location): ProblemLocationSnapshot? {
        val path = (location as? FileLocation)?.path?.takeIf { it.isNotBlank() }
        val lineInFile = location as? LineInFileLocation
        val line = lineInFile?.line?.takeIf { it > 0 }
        val column = lineInFile?.column?.takeIf { it > 0 }
        if (path == null && line == null) {
            return null
        }
        return ProblemLocationSnapshot(path = path, line = line, column = column)
    }

    private fun locationResponse(locations: List<ProblemLocationSnapshot>): List<Map<String, Any?>>? {
        if (locations.isEmpty()) {
            return null
        }
        return locations.map { location ->
            buildMap {
                location.path?.let { put("path", it) }
                location.line?.let { put("line", it) }
                location.column?.let { put("column", it) }
            }
        }
    }

    private fun problemLabel(problemId: ProblemId): String =
        problemId.displayName.takeIf { it.isNotBlank() } ?: problemId.name

    private fun severityName(severity: Severity): String? =
        when {
            severity === Severity.ERROR -> "error"
            severity === Severity.WARNING -> "warning"
            severity === Severity.ADVICE -> "advice"
            !severity.isKnown -> "unknown"
            else -> null
        }
}
