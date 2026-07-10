package com.example.gradle.mcp.build

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class TaskProgressKeyTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("gradleTaskDisplayNames")
    fun `fromDisplayName extracts task path from Gradle task progress display names`(
        displayName: String,
        expectedKey: String,
    ) {
        TaskProgressKey.fromDisplayName(displayName) shouldBe expectedKey
    }

    companion object {
        @JvmStatic
        fun gradleTaskDisplayNames(): Stream<Arguments> = Stream.of(
            Arguments.of("Task :app:compile started", ":app:compile"),
            Arguments.of("Task :app:compile SUCCESS", ":app:compile"),
            Arguments.of("Task :app:compile UP-TO-DATE", ":app:compile"),
            Arguments.of("Task :app:compile FROM-CACHE", ":app:compile"),
            Arguments.of("Task :app:compile SKIPPED", ":app:compile"),
            Arguments.of("Task :app:compile NO-SOURCE", ":app:compile"),
            Arguments.of("Task :app:compile failed", ":app:compile"),
            Arguments.of("Test com.example.FooTest.testBar", "com.example.FooTest.testBar"),
            Arguments.of("Test com.example.FooTest.my method name", "com.example.FooTest.my method name"),
            Arguments.of(":app:compile", ":app:compile"),
        )
    }
}
