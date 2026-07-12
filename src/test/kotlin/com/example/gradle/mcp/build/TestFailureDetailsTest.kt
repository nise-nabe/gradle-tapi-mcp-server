package com.example.gradle.mcp.build

import io.kotest.matchers.shouldBe
import org.gradle.tooling.Failure
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class TestFailureDetailsTest {
    @Test
    fun `exceptionTypeFromFailure parses qualified class from description`() {
        val failure = failureProxy(
            message = "expected:<1> but was:<0>",
            description = "junit.framework.AssertionFailedError: expected:<1> but was:<0>",
        )

        TestFailureDetails.exceptionTypeFromFailure(failure) shouldBe "junit.framework.AssertionFailedError"
    }

    private fun failureProxy(message: String, description: String): Failure =
        Proxy.newProxyInstance(
            Failure::class.java.classLoader,
            arrayOf(Failure::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getMessage" -> message
                    "getDescription" -> description
                    "getCauses", "getProblems" -> emptyList<Any>()
                    else -> null
                }
            },
        ) as Failure
}
