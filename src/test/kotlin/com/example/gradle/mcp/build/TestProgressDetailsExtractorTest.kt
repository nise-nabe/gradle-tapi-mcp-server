package com.example.gradle.mcp.build

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestProgressDetailsExtractorTest {
    @Test
    fun `fromDiskMap omits failureMessage for non-fail test events`() {
        val details = TestProgressDetailsExtractor.fromDiskMap(
            ProgressEventTypes.TEST_SKIP,
            mapOf(
                "className" to "com.example.FooTest",
                "methodName" to "bar",
                "outcome" to "skipped due to assumption",
                "failureMessage" to "skipped due to assumption",
            ),
        ).shouldNotBeNull()

        details.failureMessage.shouldBeNull()
    }

    @Test
    fun `fromDiskMap keeps failureMessage for failed test events`() {
        val details = TestProgressDetailsExtractor.fromDiskMap(
            ProgressEventTypes.TEST_FAIL,
            mapOf(
                "className" to "com.example.FooTest",
                "methodName" to "bar",
                "outcome" to "boom",
            ),
        ).shouldNotBeNull()

        details.failureMessage shouldBe "boom"
    }

    @Test
    fun `fromDiskMap derives failureType from exceptionType when the event omits it`() {
        val assertion = TestProgressDetailsExtractor.fromDiskMap(
            ProgressEventTypes.TEST_FAIL,
            mapOf(
                "className" to "com.example.FooTest",
                "methodName" to "bar",
                "exceptionType" to "junit.framework.AssertionFailedError",
                "failureMessage" to "expected:<1> but was:<0>",
            ),
        ).shouldNotBeNull()
        val framework = TestProgressDetailsExtractor.fromDiskMap(
            ProgressEventTypes.TEST_FAIL,
            mapOf(
                "exceptionType" to "java.lang.IllegalStateException",
            ),
        ).shouldNotBeNull()

        assertion.failureType shouldBe "assertion"
        assertion.exceptionType shouldBe "junit.framework.AssertionFailedError"
        framework.failureType shouldBe "framework"
    }

    @Test
    fun `fromDiskMap keeps a recorded failureType over the exceptionType derivation`() {
        val details = TestProgressDetailsExtractor.fromDiskMap(
            ProgressEventTypes.TEST_FAIL,
            mapOf(
                "exceptionType" to "junit.framework.AssertionFailedError",
                "failureType" to "framework",
            ),
        ).shouldNotBeNull()

        details.failureType shouldBe "framework"
    }

    @Test
    fun `fromDiskMap restores failureType for failed test events`() {
        val details = TestProgressDetailsExtractor.fromDiskMap(
            ProgressEventTypes.TEST_FAIL,
            mapOf(
                "className" to "com.example.FooTest",
                "failureType" to "framework",
            ),
        ).shouldNotBeNull()

        details.failureType shouldBe "framework"
    }

    @Test
    fun `fromDiskMap normalizes sourcePath separators`() {
        val details = TestProgressDetailsExtractor.fromDiskMap(
            ProgressEventTypes.TEST_START,
            mapOf(
                "className" to "com.example.FooTest",
                "sourcePath" to "src\\test\\kotlin\\com\\example\\FooTest.kt",
            ),
        ).shouldNotBeNull()

        details.sourcePath shouldBe "src/test/kotlin/com/example/FooTest.kt"
    }
}
