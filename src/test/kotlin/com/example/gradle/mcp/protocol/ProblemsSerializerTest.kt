package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProblemSnapshot
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.problems.ContextualLabel
import org.gradle.tooling.events.problems.Details
import org.gradle.tooling.events.problems.Problem
import org.gradle.tooling.events.problems.ProblemDefinition
import org.gradle.tooling.events.problems.ProblemId
import org.gradle.tooling.events.problems.Severity
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

    private fun problemProxy(
        displayName: String,
        details: String?,
        severity: Severity,
        solutions: List<String>,
        contextualLabel: String?,
    ): Problem {
        val problemId = Proxy.newProxyInstance(
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
                    "getSolutions" -> solutions.map { solutionText ->
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
                    "getOriginLocations", "getContextualLocations", "getFailure", "getAdditionalData" -> emptyList<Any>()
                    else -> null
                }
            },
        ) as Problem
    }
}
