package com.example.gradle.mcp.build

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BuildStatusWaitOptionsTest {
    @Test
    fun `fromArgs applies defaults`() {
        val options = BuildStatusWaitOptions.fromArgs(emptyMap())

        options.waitUntilComplete shouldBe false
        options.waitTimeoutMs shouldBe BuildStatusWaitOptions.DEFAULT_WAIT_TIMEOUT_MS
        options.pollIntervalMs shouldBe BuildStatusWaitOptions.DEFAULT_POLL_INTERVAL_MS
    }

    @Test
    fun `fromArgs caps wait timeout`() {
        val options = BuildStatusWaitOptions.fromArgs(
            mapOf(
                "waitUntilComplete" to true,
                "waitTimeoutMs" to 999_999,
                "pollIntervalMs" to 0,
            ),
        )

        options.waitUntilComplete shouldBe true
        options.waitTimeoutMs shouldBe BuildStatusWaitOptions.MAX_WAIT_TIMEOUT_MS
        options.pollIntervalMs shouldBe 1
    }
}
