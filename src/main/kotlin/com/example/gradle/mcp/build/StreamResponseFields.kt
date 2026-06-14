package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.model.OutputLimiter

internal fun streamResponseFields(
    snapshot: CapturedStreamSnapshot,
    outputLimit: OutputLimitOptions,
    fieldPrefix: String,
): Map<String, Any?> {
    if (!outputLimit.includeOutput) {
        return emptyMap()
    }
    if (snapshot.text.isEmpty() && snapshot.totalChars == 0) {
        return emptyMap()
    }
    val limited = OutputLimiter.limit(snapshot.text, outputLimit)
    return mapOf(
        fieldPrefix to limited.text,
        "${fieldPrefix}Truncated" to (limited.truncated || limited.totalChars < snapshot.totalChars),
        "${fieldPrefix}TotalChars" to snapshot.totalChars,
    )
}
