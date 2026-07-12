package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalNonNegativeInt

data class OutputLimitOptions(
    val includeOutput: Boolean = false,
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    val tailOutput: Boolean = true,
    val sinceStdoutOffset: Int? = null,
    val sinceStderrOffset: Int? = null,
) {
    companion object {
        const val DEFAULT_MAX_OUTPUT_CHARS = 8_000

        fun fromArgs(args: Map<String, Any>): OutputLimitOptions =
            OutputLimitOptions(
                includeOutput = args.optionalBoolean("includeOutput", default = false),
                maxOutputChars = args.optionalNonNegativeInt("maxOutputChars") ?: DEFAULT_MAX_OUTPUT_CHARS,
                tailOutput = args.optionalBoolean("tailOutput", default = true),
                sinceStdoutOffset = args.optionalNonNegativeInt("sinceStdoutOffset"),
                sinceStderrOffset = args.optionalNonNegativeInt("sinceStderrOffset"),
            )
    }

    fun sinceOffset(fieldPrefix: String): Int? =
        when (fieldPrefix) {
            "stdout" -> sinceStdoutOffset
            "stderr" -> sinceStderrOffset
            else -> null
        }
}
