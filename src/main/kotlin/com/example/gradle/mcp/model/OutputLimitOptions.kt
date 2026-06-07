package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.optionalBoolean

data class OutputLimitOptions(
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    val tailOutput: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_OUTPUT_CHARS = 8_000

        fun fromArgs(args: Map<String, Any>): OutputLimitOptions =
            OutputLimitOptions(
                maxOutputChars = args.optionalPositiveInt("maxOutputChars") ?: DEFAULT_MAX_OUTPUT_CHARS,
                tailOutput = args.optionalBoolean("tailOutput", default = true),
            )
    }
}
