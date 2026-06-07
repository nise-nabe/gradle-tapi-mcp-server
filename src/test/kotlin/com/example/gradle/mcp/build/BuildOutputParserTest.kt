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
    }

    @Test
    fun `maps terminal build status to outcome`() {
        assertEquals("SUCCESS", BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_SUCCEEDED))
        assertEquals("FAILED", BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_FAILED))
        assertNull(BuildOutputParser.outcomeFromStatus(BuildProgressTracker.STATUS_RUNNING))
    }
}

