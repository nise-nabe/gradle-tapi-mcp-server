package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalNonNegativeInt

data class BuildStatusWaitOptions(
    val waitUntilComplete: Boolean = false,
    val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    companion object {
        const val DEFAULT_WAIT_TIMEOUT_MS = 120_000L
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        const val MAX_WAIT_TIMEOUT_MS = 300_000L

        fun fromArgs(args: Map<String, Any>): BuildStatusWaitOptions {
            val requestedTimeout = args.optionalNonNegativeInt("waitTimeoutMs")?.toLong()
                ?: DEFAULT_WAIT_TIMEOUT_MS
            val requestedPoll = args.optionalNonNegativeInt("pollIntervalMs")?.toLong()
                ?: DEFAULT_POLL_INTERVAL_MS
            return BuildStatusWaitOptions(
                waitUntilComplete = args.optionalBoolean("waitUntilComplete", default = false),
                waitTimeoutMs = requestedTimeout.coerceIn(0, MAX_WAIT_TIMEOUT_MS),
                pollIntervalMs = requestedPoll.coerceIn(1, MAX_WAIT_TIMEOUT_MS),
            )
        }
    }
}
