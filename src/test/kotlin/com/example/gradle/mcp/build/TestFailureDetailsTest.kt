package com.example.gradle.mcp.build

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.gradle.tooling.Failure
import org.gradle.tooling.TestAssertionFailure
import org.gradle.tooling.TestFailure
import org.gradle.tooling.TestFrameworkFailure
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

    @Test
    fun `exceptionTypeFromFailure prefers TestFailure className over description`() {
        val failure = failureProxy(
            message = "setup boom",
            description = "some.prefix.OtherError: setup boom",
            interfaces = arrayOf(TestFrameworkFailure::class.java),
            className = "java.lang.IllegalStateException",
        )

        TestFailureDetails.exceptionTypeFromFailure(failure) shouldBe "java.lang.IllegalStateException"
    }

    @Test
    fun `failureTypeFromFailure classifies framework failures`() {
        val failure = failureProxy(
            message = "setup boom",
            description = null,
            interfaces = arrayOf(TestFrameworkFailure::class.java),
            className = "java.lang.IllegalStateException",
        )

        TestFailureDetails.failureTypeFromFailure(failure) shouldBe TestFailureDetails.FAILURE_TYPE_FRAMEWORK
    }

    @Test
    fun `failureTypeFromFailure classifies assertion failures`() {
        val failure = failureProxy(
            message = "expected:<1> but was:<0>",
            description = null,
            interfaces = arrayOf(TestAssertionFailure::class.java),
            className = "junit.framework.AssertionFailedError",
        )

        TestFailureDetails.failureTypeFromFailure(failure) shouldBe TestFailureDetails.FAILURE_TYPE_ASSERTION
    }

    @Test
    fun `failureTypeFromFailure returns null for plain failures`() {
        val failure = failureProxy(
            message = "boom",
            description = null,
            interfaces = arrayOf(TestFailure::class.java),
        )

        TestFailureDetails.failureTypeFromFailure(failure).shouldBeNull()
    }

    private fun failureProxy(
        message: String,
        description: String?,
        interfaces: Array<Class<*>> = arrayOf(Failure::class.java),
        className: String? = null,
    ): Failure =
        Proxy.newProxyInstance(
            Failure::class.java.classLoader,
            interfaces,
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getMessage" -> message
                    "getDescription" -> description
                    "getClassName" -> className
                    "getCauses", "getProblems" -> emptyList<Any>()
                    else -> null
                }
            },
        ) as Failure
}
