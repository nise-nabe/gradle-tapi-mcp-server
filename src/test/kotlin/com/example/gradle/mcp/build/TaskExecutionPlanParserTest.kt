package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.support.succeededTracker
import com.example.gradle.mcp.support.testBuildRecord
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TaskExecutionPlanParserTest {
    @Test
    fun `parses ordered dry-run task lines`() {
        val stdout = """
            :compileKotlin SKIPPED
            :compileJava SKIPPED
            :app:processResources SKIPPED
            :classes SKIPPED
            :jar SKIPPED

            BUILD SUCCESSFUL in 1s
        """.trimIndent()

        val plan = TaskExecutionPlanParser.parse(stdout)

        plan.map { it.path } shouldBe listOf(
            ":compileKotlin",
            ":compileJava",
            ":app:processResources",
            ":classes",
            ":jar",
        )
        plan.map { it.state }.distinct() shouldBe listOf("SKIPPED")
    }

    @Test
    fun `ignores non-plan lines and unknown states`() {
        val stdout = """
            > Configure project :
            :compileJava SKIPPED
            :debug CUSTOMPRINT
            > Task :compileJava
            1 actionable task: 1 up-to-date
            BUILD SUCCESSFUL in 1s
        """.trimIndent()

        val plan = TaskExecutionPlanParser.parse(stdout)

        plan.map { it.path } shouldBe listOf(":compileJava")
    }

    @Test
    fun `keeps known non-skipped states when present`() {
        val stdout = """
            :compileJava UP-TO-DATE
            :jar FROM-CACHE
            :test SKIPPED
        """.trimIndent()

        val plan = TaskExecutionPlanParser.parse(stdout)

        plan.map { it.state } shouldBe listOf("UP-TO-DATE", "FROM-CACHE", "SKIPPED")
    }

    @Test
    fun `returns empty plan for blank or unrelated output`() {
        TaskExecutionPlanParser.parse("") shouldBe emptyList()
        TaskExecutionPlanParser.parse("plain log output") shouldBe emptyList()
    }

    @Test
    fun `handles CRLF line endings`() {
        val plan = TaskExecutionPlanParser.parse(":a SKIPPED\r\n:b SKIPPED\r\n")

        plan.map { it.path } shouldBe listOf(":a", ":b")
    }

    @Test
    fun `toResponseMap exposes taskCount and ordered entries`() {
        val response = TaskExecutionPlanParser.toResponseMap(
            listOf(
                TaskExecutionPlanParser.PlannedTask(":compileJava", "SKIPPED"),
                TaskExecutionPlanParser.PlannedTask(":jar", "SKIPPED"),
            ),
        )

        response["taskCount"] shouldBe 2
        response["tasks"] shouldBe listOf(
            mapOf("path" to ":compileJava", "state" to "SKIPPED"),
            mapOf("path" to ":jar", "state" to "SKIPPED"),
        )
    }

    @Test
    fun `finished plan build record exposes taskPlan in status view and response`() {
        val record = testBuildRecord(
            id = "plan-build",
            kind = BuildKind.PLAN,
            tasks = listOf("build"),
            tracker = succeededTracker(),
            streams = CapturingStreams().also {
                it.appendStdoutForTests(
                    ":compileJava SKIPPED\n:jar SKIPPED\nBUILD SUCCESSFUL in 1s\n",
                )
            },
        )

        val view = BuildStatusView.fromRecord(record)
        val response = BuildStatusAssembler.assemble(
            view = view,
            outputLimit = OutputLimitOptions(),
            progressOptions = ProgressResponseOptions(),
        )

        (response["taskPlan"] as Map<*, *>)["taskCount"] shouldBe 2
        (response["taskPlan"] as Map<*, *>)["tasks"] shouldBe listOf(
            mapOf("path" to ":compileJava", "state" to "SKIPPED"),
            mapOf("path" to ":jar", "state" to "SKIPPED"),
        )
    }

    @Test
    fun `non-plan builds do not expose taskPlan`() {
        val record = testBuildRecord(
            id = "tasks-build",
            kind = BuildKind.TASKS,
            tracker = succeededTracker(),
            streams = CapturingStreams().also {
                it.appendStdoutForTests("BUILD SUCCESSFUL in 1s\n")
            },
        )

        val view = BuildStatusView.fromRecord(record)

        view.taskPlan shouldBe null
    }
}
