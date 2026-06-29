package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProblemSnapshot
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.problems.ContextualLabel
import org.gradle.tooling.events.problems.Details
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
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class ProblemsSerializerTest {
    @Test
    fun `toResponseMaps serializes problem fields`() {
        val problems = listOf(
            BuildProblemSnapshot(
                label = "Compilation failed",
                details = "cannot find symbol",
                severity = "error",
                solutions = listOf("Check dependencies"),
                contextualLabel = "Task :compileJava",
            ),
        )

        val response = ProblemsSerializer.toResponseMaps(problems).single()

        response["label"] shouldBe "Compilation failed"
        response["details"] shouldBe "cannot find symbol"
        response["severity"] shouldBe "error"
        response["solutions"] shouldBe listOf("Check dependencies")
        response["contextualLabel"] shouldBe "Task :compileJava"
    }

    @Test
    fun `fromFailureResult extracts problems from failures and nested causes`() {
        val problem = problemProxy(
            displayName = "Missing dependency",
            details = "Could not resolve com.example:lib:1.0",
            severity = Severity.ERROR,
            solutions = listOf("Add the dependency to your build file"),
            contextualLabel = "configuration ':app'",
        )
        val cause = failureProxy(message = "cause", problems = emptyList())
        val failure = failureProxy(
            message = "Build failed",
            problems = listOf(problem),
            causes = listOf(cause),
        )
        val result = failureResultProxy(listOf(failure))

        val extracted = ProblemsSerializer.fromFailureResult(result)

        extracted shouldHaveSize 1
        extracted.single().label shouldBe "Missing dependency"
        extracted.single().severity shouldBe "error"
    }

    @Test
    fun `fromProblemEvent extracts single problems`() {
        val problem = problemProxy(
            displayName = "Deprecated API usage",
            details = "Task uses a deprecated input property",
            severity = Severity.WARNING,
            solutions = listOf("Use the new property"),
            contextualLabel = "Task :compileJava",
        )

        val extracted = ProblemsSerializer.fromProblemEvent(singleProblemEventProxy(problem))

        extracted shouldHaveSize 1
        extracted.single().label shouldBe "Deprecated API usage"
        extracted.single().severity shouldBe "warning"
        extracted.single().contextualLabel shouldBe "Task :compileJava"
    }

    @Test
    fun `fromProblemEvent expands problem aggregations into snapshots`() {
        val problem = problemProxy(
            displayName = "Compilation failed",
            details = null,
            severity = Severity.ERROR,
            solutions = emptyList(),
            contextualLabel = null,
        )
        val aggregation = problemAggregationProxy(
            problem = problem,
            contexts = listOf(
                problemContextProxy(
                    details = "cannot find symbol",
                    solutions = listOf("Add the missing dependency"),
                ),
            ),
        )

        val extracted = ProblemsSerializer.fromProblemEvent(problemAggregationEventProxy(aggregation))

        extracted shouldHaveSize 1
        extracted.single().label shouldBe "Compilation failed"
        extracted.single().details shouldBe "cannot find symbol"
        extracted.single().solutions shouldBe listOf("Add the missing dependency")
    }

    @Test
    fun `fromProblemEvent converts summaries into compact snapshots`() {
        val problem = problemProxy(
            displayName = "Deprecated API usage",
            details = null,
            severity = Severity.WARNING,
            solutions = emptyList(),
            contextualLabel = null,
        )

        val extracted = ProblemsSerializer.fromProblemEvent(
            problemSummariesEventProxy(listOf(problemSummaryProxy(problem, 3))),
        )

        extracted shouldHaveSize 1
        extracted.single().label shouldBe "Deprecated API usage"
        extracted.single().details shouldBe "Occurred 3 times"
    }

    @Test
    fun `mergeDistinct keeps first occurrence of duplicate problems`() {
        val target = mutableListOf(
            BuildProblemSnapshot(label = "A", details = "same"),
            BuildProblemSnapshot(label = "B"),
        )
        val additions = listOf(
            BuildProblemSnapshot(label = "A", details = "same"),
            BuildProblemSnapshot(label = "C"),
        )

        ProblemsSerializer.mergeDistinct(target, additions)

        target shouldHaveSize 3
        target.map { it.label } shouldBe listOf("A", "B", "C")
    }

    @Test
    fun `mergeDistinct unions solutions when duplicate problems are merged`() {
        val target = mutableListOf(
            BuildProblemSnapshot(label = "A", details = "same", solutions = listOf("Fix A")),
        )
        val additions = listOf(
            BuildProblemSnapshot(label = "A", details = "same", solutions = listOf("Fix B", "Fix A")),
        )

        ProblemsSerializer.mergeDistinct(target, additions)

        target shouldHaveSize 1
        target.single().solutions shouldBe listOf("Fix A", "Fix B")
    }

    @Test
    fun `mergeDistinct treats details and contextualLabel as distinct positions`() {
        val target = mutableListOf<BuildProblemSnapshot>()
        val additions = listOf(
            BuildProblemSnapshot(label = "L", details = null, contextualLabel = "X"),
            BuildProblemSnapshot(label = "L", details = "X", contextualLabel = null),
        )

        ProblemsSerializer.mergeDistinct(target, additions)

        target shouldHaveSize 2
    }

    @Test
    fun `fromFailure maps unknown severity to unknown`() {
        val problem = problemProxy(
            displayName = "Future problem",
            details = "details",
            severity = unknownSeverityProxy(),
            solutions = emptyList(),
            contextualLabel = null,
        )
        val failure = failureProxy(message = "Build failed", problems = listOf(problem))

        val extracted = ProblemsSerializer.fromFailure(failure)

        extracted shouldHaveSize 1
        extracted.single().severity shouldBe "unknown"
    }

    private fun unknownSeverityProxy(): Severity =
        Proxy.newProxyInstance(
            Severity::class.java.classLoader,
            arrayOf(Severity::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "isKnown" -> false
                    "toString" -> "UNKNOWN_FUTURE_SEVERITY"
                    else -> null
                }
            },
        ) as Severity

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
        message: String,
        problems: List<Problem>,
        causes: List<Failure> = emptyList(),
    ): Failure =
        Proxy.newProxyInstance(
            Failure::class.java.classLoader,
            arrayOf(Failure::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getMessage" -> message
                    "getDescription" -> null
                    "getProblems" -> problems
                    "getCauses" -> causes
                    else -> null
                }
            },
        ) as Failure

    private fun singleProblemEventProxy(problem: Problem): SingleProblemEvent =
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

    private fun problemAggregationEventProxy(aggregation: ProblemAggregation): ProblemAggregationEvent =
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

    private fun problemSummariesEventProxy(summaries: List<ProblemSummary>): ProblemSummariesEvent =
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

    private fun problemAggregationProxy(
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

    private fun problemContextProxy(
        details: String?,
        solutions: List<String>,
        failureMessage: String? = null,
    ): ProblemContext =
        Proxy.newProxyInstance(
            ProblemContext::class.java.classLoader,
            arrayOf(ProblemContext::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getDetails" -> details?.let(::detailsProxy)
                    "getSolutions" -> solutions.map(::solutionProxy)
                    "getFailure" -> failureMessage?.let { failureProxy(it, emptyList()) }
                    "getOriginLocations", "getContextualLocations" -> emptyList<Any>()
                    else -> null
                }
            },
        ) as ProblemContext

    private fun problemSummaryProxy(problem: Problem, count: Int?): ProblemSummary =
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

    private fun problemProxy(
        displayName: String,
        details: String?,
        severity: Severity,
        solutions: List<String>,
        contextualLabel: String?,
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
                    "getOriginLocations", "getContextualLocations", "getFailure", "getAdditionalData" -> emptyList<Any>()
                    else -> null
                }
            },
        ) as Problem
    }

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
}
