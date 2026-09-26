package com.example.gradle.mcp.model

import com.example.gradle.mcp.support.defaultProxyReturn
import com.example.gradle.mcp.support.proxyIdentity
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.gradle.tooling.Failure
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class FailureRecordsTest {
    @Test
    fun `caps failures at twenty and marks truncated`() {
        val failures = (1..21).map { toolingFailureProxy("failure-$it") }

        val slice = FailureRecords.fromFailures(failures)

        slice.records shouldHaveSize FailureRecords.MAX_FAILURES
        slice.isTruncated shouldBe true
        slice.records.first().message shouldBe "failure-1"
        slice.records.last().message shouldBe "failure-20"
    }

    @Test
    fun `truncates long messages`() {
        val longMessage = "x".repeat(FailureRecords.MAX_MESSAGE_CHARS + 25)

        val slice = FailureRecords.fromFailures(listOf(toolingFailureProxy(longMessage)))

        slice.records.single().message?.length shouldBe FailureRecords.MAX_MESSAGE_CHARS
        slice.isTruncated shouldBe false
    }

    @Test
    fun `caps nested causes by depth and count`() {
        val deep = toolingFailureProxy(
            message = "root",
            causes = listOf(
                toolingFailureProxy(
                    message = "cause-1",
                    causes = listOf(
                        toolingFailureProxy(
                            message = "cause-2",
                            causes = listOf(toolingFailureProxy("cause-3")),
                        ),
                    ),
                ),
            ),
        )

        val slice = FailureRecords.fromFailures(listOf(deep))

        val root = slice.records.single()
        root.message shouldBe "root"
        root.causes.shouldHaveSize(1)
        root.causes.single().message shouldBe "cause-1"
        root.causes.single().causes.shouldHaveSize(1)
        root.causes.single().causes.single().message shouldBe "cause-2"
        root.causes.single().causes.single().causes.shouldHaveSize(0)
        root.causeMessages shouldBe listOf("cause-1", "cause-2")
    }

    @Test
    fun `copies problem display names`() {
        val failure = toolingFailureProxy(
            message = "configure failed",
            problems = listOf(labeledProblem("Compilation failed")),
        )

        val slice = FailureRecords.fromFailures(listOf(failure))

        slice.records.single().problems shouldBe listOf("Compilation failed")
    }

    @Test
    fun `prefers own description over aggregated description`() {
        val failure = toolingFailureProxy(
            message = "root",
            description = "root stack\nCaused by: cause stack",
            ownDescription = "root stack",
            causes = listOf(
                toolingFailureProxy(
                    message = "cause-1",
                    description = "cause stack\nCaused by: nested stack",
                    ownDescription = "cause stack",
                ),
            ),
        )

        val slice = FailureRecords.fromFailures(listOf(failure))

        val root = slice.records.single()
        root.description shouldBe "root stack"
        root.causes.single().description shouldBe "cause stack"
    }

    @Test
    fun `falls back to description when own description is unavailable`() {
        val failure = toolingFailureProxy(
            message = "root",
            description = "aggregated description",
        )

        val slice = FailureRecords.fromFailures(listOf(failure))

        slice.records.single().description shouldBe "aggregated description"
    }

    @Test
    fun `falls back to description when failure lacks getOwnDescription`() {
        val failure = Proxy.newProxyInstance(
            Failure::class.java.classLoader,
            arrayOf(Failure::class.java),
        ) { proxy, method, args ->
            proxyIdentity(proxy, method.name, args) ?: when (method.name) {
                "getMessage" -> "root"
                "getDescription" -> "aggregated description"
                "getOwnDescription" -> throw AbstractMethodError()
                else -> defaultProxyReturn(method)
            }
        } as Failure

        val slice = FailureRecords.fromFailures(listOf(failure))

        slice.records.single().description shouldBe "aggregated description"
    }

    @Test
    fun `uses unknown failure when all fields are empty`() {
        val slice = FailureRecords.fromFailures(listOf(toolingFailureProxy(message = null)))

        slice.records.single().message shouldBe "Unknown failure"
    }
}
