package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalNonNegativeInt

data class HelpLimitOptions(
    val maxChars: Int = DEFAULT_MAX_CHARS,
    val tailOutput: Boolean = true,
) {
    fun toOutputLimitOptions(): OutputLimitOptions =
        OutputLimitOptions(
            includeOutput = true,
            maxOutputChars = maxChars,
            tailOutput = tailOutput,
        )

    companion object {
        const val DEFAULT_MAX_CHARS = 8_000

        fun fromArgs(args: Map<String, Any>): HelpLimitOptions =
            HelpLimitOptions(
                maxChars = args.optionalNonNegativeInt("maxChars") ?: DEFAULT_MAX_CHARS,
                tailOutput = args.optionalBoolean("tailOutput", default = true),
            )
    }
}
