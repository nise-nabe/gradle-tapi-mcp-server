package com.example.gradle.mcp.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuildOutputParserTest {
    @Test
    fun `parses build result and task summary from stdout tail`() {
        val stdout = """
            > Task :compileJava
            > Task :test

            BUILD SUCCESSFUL in 3s
            5 actionable tasks: 3 executed, 2 up-to-date
        """.trimIndent()

        val summary = BuildOutputParser.parse(stdout)

        assertEquals("BUILD SUCCESSFUL in 3s", summary.resultLine)
        assertEquals("5 actionable tasks: 3 executed, 2 up-to-date", summary.taskSummaryLine)
    }


    @Test
    fun `parses up-to-date-only task summary`() {
        val stdout = """
            BUILD SUCCESSFUL in 1s
            2 actionable tasks: 2 up-to-date
        """.trimIndent()

        val summary = BuildOutputParser.parse(stdout)

        assertEquals("BUILD SUCCESSFUL in 1s", summary.resultLine)
        assertEquals("2 actionable tasks: 2 up-to-date", summary.taskSummaryLine)
    }
    @Test
    fun `returns null lines when stdout has no summary`() {
        val summary = BuildOutputParser.parse("plain log output")

        assertNull(summary.resultLine)
        assertNull(summary.taskSummaryLine)
    }

    @Test
    fun `parses failed build result line`() {
        val summary = BuildOutputParser.parse(
            """
            > Task :compileJava FAILED
            BUILD FAILED in 2s
            1 actionable task: 1 executed
            """.trimIndent(),
        )

        assertEquals("BUILD FAILED in 2s", summary.resultLine)
        assertEquals("1 actionable task: 1 executed", summary.taskSummaryLine)
        assertEquals(listOf(":compileJava"), summary.failureSummary)
    }

    @Test
    fun `parses failed task and test lines into failureSummary`() {
        val summary = BuildOutputParser.parse(
            """
            > Task :examples:resilience4j-spring:test FAILED
            > com.linecorp.armeria.example.resilience4j.spring.CircuitBreakerTest > testActuator() FAILED
                com.linecorp.armeria.client.ResponseTimeoutException at CircuitBreakerTest.java:42
            BUILD FAILED in 6m 26s
            4 tests completed, 1 failed
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ":examples:resilience4j-spring:test",
                "com.linecorp.armeria.example.resilience4j.spring.CircuitBreakerTest > testActuator()",
            ),
            summary.failureSummary,
        )
    }

    @Test
    fun `toResponseMap omits empty failureSummary`() {
        val response = BuildOutputParser.toResponseMap(
            BuildSummary(resultLine = "BUILD SUCCESSFUL in 1s", taskSummaryLine = null),
        )

        assertEquals("BUILD SUCCESSFUL in 1s", response["resultLine"])
        assertEquals(false, response.containsKey("failureSummary"))
    }

    @Test
    fun `toResponseMap includes failureSummary when present`() {
        val response = BuildOutputParser.toResponseMap(
            BuildSummary(
                resultLine = "BUILD FAILED in 2s",
                taskSummaryLine = null,
                failureSummary = listOf(":compileJava"),
            ),
        )

        assertEquals(listOf(":compileJava"), response["failureSummary"])
    }

    @Test
    fun `maps terminal build status to outcome`() {
        assertEquals("SUCCESS", BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_SUCCEEDED))
        assertEquals("FAILED", BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_FAILED))
        assertEquals("CANCELLED", BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_CANCELLED))
        assertNull(BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_RUNNING))
    }
}

