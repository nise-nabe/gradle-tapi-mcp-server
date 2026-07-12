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
    val sinceOffset = outputLimit.sinceOffset(fieldPrefix)
    if (sinceOffset != null) {
        return deltaStreamResponseFields(snapshot, outputLimit, fieldPrefix, sinceOffset)
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

private fun deltaStreamResponseFields(
    snapshot: CapturedStreamSnapshot,
    outputLimit: OutputLimitOptions,
    fieldPrefix: String,
    sinceOffset: Int,
): Map<String, Any?> {
    val totalChars = snapshot.totalChars
    val retainedStartOffset = (totalChars - snapshot.text.length).coerceAtLeast(0)
    val effectiveSince = sinceOffset.coerceIn(0, totalChars).coerceAtLeast(retainedStartOffset)
    val deltaStartInText = (effectiveSince - retainedStartOffset).coerceIn(0, snapshot.text.length)
    val deltaText = snapshot.text.substring(deltaStartInText)
    val limited = OutputLimiter.limit(deltaText, outputLimit)
    return buildMap {
        put("${fieldPrefix}Offset", totalChars)
        if (deltaText.isNotEmpty() || sinceOffset < totalChars) {
            put("${fieldPrefix}Delta", limited.text)
            put(
                "${fieldPrefix}DeltaTruncated",
                limited.truncated || effectiveSince > sinceOffset,
            )
        } else {
            put("${fieldPrefix}Delta", "")
            put("${fieldPrefix}DeltaTruncated", false)
        }
    }
}
