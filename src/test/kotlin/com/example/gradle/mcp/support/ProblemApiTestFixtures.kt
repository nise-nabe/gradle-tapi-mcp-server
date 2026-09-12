package com.example.gradle.mcp.support

import org.gradle.tooling.events.problems.ContextualLabel
import org.gradle.tooling.events.problems.Details
import org.gradle.tooling.events.problems.FileLocation
import org.gradle.tooling.events.problems.LineInFileLocation
import org.gradle.tooling.events.problems.Location
import org.gradle.tooling.events.problems.Problem
import org.gradle.tooling.events.problems.ProblemAggregation
import org.gradle.tooling.events.problems.ProblemAggregationEvent
import org.gradle.tooling.events.problems.ProblemContext
import org.gradle.tooling.events.problems.ProblemDefinition
import org.gradle.tooling.events.problems.ProblemId
import org.gradle.tooling.events.problems.ProblemSummariesEvent
import org.gradle.tooling.events.problems.ProblemSummary
import org.gradle.tooling.events.problems.Severity
import org.gradle.tooling.events.problems.SingleProblemEvent
import org.gradle.tooling.events.problems.Solution
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal fun singleProblemEventProxy(problem: Problem): SingleProblemEvent =
    Proxy.newProxyInstance(
        SingleProblemEvent::class.java.classLoader,
        arrayOf(SingleProblemEvent::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getProblem" -> problem
                "getDisplayName" -> "Problem: ${problem.definition.id.displayName}"
                "getEventTime" -> 0L
                else -> null
            }
        },
    ) as SingleProblemEvent

internal fun problemAggregationEventProxy(aggregation: ProblemAggregation): ProblemAggregationEvent =
    Proxy.newProxyInstance(
        ProblemAggregationEvent::class.java.classLoader,
        arrayOf(ProblemAggregationEvent::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getProblemAggregation" -> aggregation
                "getDisplayName" -> "Problem aggregation"
                "getEventTime" -> 0L
                else -> null
            }
        },
    ) as ProblemAggregationEvent

internal fun problemSummariesEventProxy(summaries: List<ProblemSummary>): ProblemSummariesEvent =
    Proxy.newProxyInstance(
        ProblemSummariesEvent::class.java.classLoader,
        arrayOf(ProblemSummariesEvent::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getProblemSummaries" -> summaries
                "getDisplayName" -> "Problem summaries"
                "getEventTime" -> 0L
                else -> null
            }
        },
    ) as ProblemSummariesEvent

internal fun problemAggregationProxy(
    problem: Problem,
    contexts: List<ProblemContext>,
): ProblemAggregation =
    Proxy.newProxyInstance(
        ProblemAggregation::class.java.classLoader,
        arrayOf(ProblemAggregation::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDefinition" -> problem.definition
                "getProblemContext" -> contexts
                else -> null
            }
        },
    ) as ProblemAggregation

internal fun problemContextProxy(
    details: String?,
    solutions: List<String>,
    failureMessage: String? = null,
    originLocations: List<Location> = emptyList(),
    contextualLocations: List<Location> = emptyList(),
): ProblemContext =
    Proxy.newProxyInstance(
        ProblemContext::class.java.classLoader,
        arrayOf(ProblemContext::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDetails" -> details?.let(::detailsProxy)
                "getSolutions" -> solutions.map(::solutionProxy)
                "getFailure" -> failureMessage?.let { problemFailureProxy(it) }
                "getOriginLocations" -> originLocations
                "getContextualLocations" -> contextualLocations
                else -> null
            }
        },
    ) as ProblemContext

internal fun problemSummaryProxy(problem: Problem, count: Int?): ProblemSummary =
    Proxy.newProxyInstance(
        ProblemSummary::class.java.classLoader,
        arrayOf(ProblemSummary::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getProblemId" -> problem.definition.id
                "getCount" -> count
                else -> null
            }
        },
    ) as ProblemSummary

internal fun problemProxy(
    displayName: String,
    details: String?,
    severity: Severity,
    solutions: List<String> = emptyList(),
    contextualLabel: String? = null,
    originLocations: List<Location> = emptyList(),
    contextualLocations: List<Location> = emptyList(),
    locationsAvailable: Boolean = true,
): Problem {
    val definition = problemDefinitionProxy(problemIdProxy(displayName), severity)
    return Proxy.newProxyInstance(
        Problem::class.java.classLoader,
        arrayOf(Problem::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDefinition" -> definition
                "getDetails" -> details?.let(::detailsProxy)
                "getContextualLabel" -> contextualLabel?.let(::contextualLabelProxy)
                "getSolutions" -> solutions.map(::solutionProxy)
                "getOriginLocations" ->
                    if (locationsAvailable) originLocations else throw AbstractMethodError("getOriginLocations")
                "getContextualLocations" ->
                    if (locationsAvailable) contextualLocations else throw AbstractMethodError("getContextualLocations")
                "getFailure", "getAdditionalData" -> emptyList<Any>()
                else -> null
            }
        },
    ) as Problem
}

internal fun lineInFileLocationProxy(
    path: String,
    line: Int,
    column: Int = 0,
    length: Int = 0,
): LineInFileLocation =
    Proxy.newProxyInstance(
        LineInFileLocation::class.java.classLoader,
        arrayOf(LineInFileLocation::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getPath" -> path
                "getLine" -> line
                "getColumn" -> column
                "getLength" -> length
                else -> null
            }
        },
    ) as LineInFileLocation

internal fun throwingFileLocationProxy(): FileLocation =
    Proxy.newProxyInstance(
        FileLocation::class.java.classLoader,
        arrayOf(FileLocation::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getPath" -> throw RuntimeException("broken location")
                else -> null
            }
        },
    ) as FileLocation

private fun problemIdProxy(displayName: String): ProblemId =
    Proxy.newProxyInstance(
        ProblemId::class.java.classLoader,
        arrayOf(ProblemId::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDisplayName" -> displayName
                "getName" -> displayName.lowercase().replace(' ', '-')
                "getGroup" -> null
                else -> null
            }
        },
    ) as ProblemId

private fun problemDefinitionProxy(
    problemId: ProblemId,
    severity: Severity,
): ProblemDefinition =
    Proxy.newProxyInstance(
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

private fun detailsProxy(text: String): Details =
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

private fun contextualLabelProxy(text: String): ContextualLabel =
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

private fun solutionProxy(solutionText: String): Solution =
    Proxy.newProxyInstance(
        Solution::class.java.classLoader,
        arrayOf(Solution::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getSolution" -> solutionText
                else -> null
            }
        },
    ) as Solution

private fun problemFailureProxy(message: String): org.gradle.tooling.Failure =
    Proxy.newProxyInstance(
        org.gradle.tooling.Failure::class.java.classLoader,
        arrayOf(org.gradle.tooling.Failure::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getMessage" -> message
                "getDescription" -> null
                "getProblems" -> emptyList<Problem>()
                "getCauses" -> emptyList<org.gradle.tooling.Failure>()
                else -> null
            }
        },
    ) as org.gradle.tooling.Failure
