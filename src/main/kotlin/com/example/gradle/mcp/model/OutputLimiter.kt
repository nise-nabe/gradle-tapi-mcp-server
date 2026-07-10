package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.OutputNormalizer

object OutputLimiter {
    private const val TRUNCATION_MARKER_TEMPLATE = "... [truncated %s chars] ..."

    private fun truncationMarker(omittedChars: Int): String =
        TRUNCATION_MARKER_TEMPLATE.format(omittedChars)

    private fun reservedMarkerLength(): Int =
        truncationMarker(999_999_999).length + 1

    fun limit(text: String, options: OutputLimitOptions): LimitedText {
        val normalized = OutputNormalizer.normalizeNewlines(text)
        if (normalized.length <= options.maxOutputChars) {
            return LimitedText(text = normalized, truncated = false, totalChars = normalized.length)
        }

        val reservedLength = reservedMarkerLength()
        if (options.maxOutputChars <= reservedLength) {
            val excerpt = if (options.tailOutput) {
                normalized.takeLast(options.maxOutputChars)
            } else {
                normalized.take(options.maxOutputChars)
            }
            return LimitedText(
                text = excerpt,
                truncated = true,
                totalChars = normalized.length,
            )
        }

        val excerptBudget = options.maxOutputChars - reservedLength
        var excerpt = if (options.tailOutput) {
            normalized.takeLast(excerptBudget)
        } else {
            normalized.take(excerptBudget)
        }
        var omittedChars = normalized.length - excerpt.length
        var marker = truncationMarker(omittedChars)
        if (marker.length + 1 + excerpt.length > options.maxOutputChars) {
            val adjustedBudget = (options.maxOutputChars - marker.length - 1).coerceAtLeast(0)
            excerpt = if (adjustedBudget == 0) {
                ""
            } else if (options.tailOutput) {
                normalized.takeLast(adjustedBudget)
            } else {
                normalized.take(adjustedBudget)
            }
            omittedChars = normalized.length - excerpt.length
            marker = truncationMarker(omittedChars)
        }
        val text = if (marker.length + 1 + excerpt.length <= options.maxOutputChars) {
            if (options.tailOutput) {
                "$marker\n$excerpt"
            } else {
                "$excerpt\n$marker"
            }
        } else if (options.tailOutput) {
            normalized.takeLast(options.maxOutputChars)
        } else {
            normalized.take(options.maxOutputChars)
        }
        return LimitedText(
            text = text,
            truncated = true,
            totalChars = normalized.length,
        )
    }

    fun limitFields(text: String, options: OutputLimitOptions, fieldPrefix: String): Map<String, Any?> {
        val limited = limit(text, options)
        return mapOf(
            fieldPrefix to limited.text,
            "${fieldPrefix}Truncated" to limited.truncated,
            "${fieldPrefix}TotalChars" to limited.totalChars,
        )
    }
}
