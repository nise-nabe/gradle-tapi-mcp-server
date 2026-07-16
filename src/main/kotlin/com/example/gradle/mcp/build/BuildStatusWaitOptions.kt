package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalNonNegativeInt

data class BuildStatusWaitOptions(
    val waitUntilComplete: Boolean = false,
    val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    companion object {
        /**
         * Kept short so a single `waitUntilComplete` call is less likely to exceed
         * MCP host/client transport timeouts (independent of this server-side wait).
         */
        const val DEFAULT_WAIT_TIMEOUT_MS = 30_000L
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        /** Soft cap below typical multi-minute host request timeouts. */
        const val MAX_WAIT_TIMEOUT_MS = 60_000L

        const val WAIT_TIMEOUT_HINT =
            "Server-side wait timed out; poll gradle_get_build_status again " +
                "(prefer short waits or waitUntilComplete=false). " +
                "waitTimeoutMs is server-only and may exceed the MCP client transport timeout."

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
