package com.example.mcp

data class ModelQueryOptions(
    val includeTasks: Boolean = false,
    val includeTaskDetails: Boolean = false,
    val includeTaskSelectors: Boolean = false,
    val taskGroup: String? = null,
    val taskNamePrefix: String? = null,
    val maxTasks: Int? = null,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>): ModelQueryOptions =
            ModelQueryOptions(
                includeTasks = args.optionalBoolean("includeTasks", default = false),
                includeTaskDetails = args.optionalBoolean("includeTaskDetails", default = false),
                includeTaskSelectors = args.optionalBoolean("includeTaskSelectors", default = false),
                taskGroup = args.optionalString("taskGroup"),
                taskNamePrefix = args.optionalString("taskNamePrefix"),
                maxTasks = args.optionalPositiveInt("maxTasks"),
            )
    }
}

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

data class LimitedText(
    val text: String,
    val truncated: Boolean,
    val totalChars: Int,
)

object OutputLimiter {
    fun limit(text: String, options: OutputLimitOptions): LimitedText {
        val normalized = OutputNormalizer.normalizeNewlines(text)
        if (normalized.length <= options.maxOutputChars) {
            return LimitedText(text = normalized, truncated = false, totalChars = normalized.length)
        }

        val maxPrefixLength = "... [truncated 999999999 chars] ...\n".length
        if (options.maxOutputChars <= maxPrefixLength) {
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

        val excerptBudget = options.maxOutputChars - maxPrefixLength
        var excerpt = if (options.tailOutput) {
            normalized.takeLast(excerptBudget)
        } else {
            normalized.take(excerptBudget)
        }
        var omittedChars = normalized.length - excerpt.length
        var prefix = "... [truncated $omittedChars chars] ...\n"
        if (prefix.length + excerpt.length > options.maxOutputChars) {
            val adjustedBudget = (options.maxOutputChars - prefix.length).coerceAtLeast(0)
            excerpt = if (adjustedBudget == 0) {
                ""
            } else if (options.tailOutput) {
                normalized.takeLast(adjustedBudget)
            } else {
                normalized.take(adjustedBudget)
            }
            omittedChars = normalized.length - excerpt.length
            prefix = "... [truncated $omittedChars chars] ...\n"
        }
        val text = if (prefix.length + excerpt.length <= options.maxOutputChars) {
            prefix + excerpt
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

private fun Map<String, Any>.optionalBoolean(key: String, default: Boolean): Boolean =
    when (val value = this[key]) {
        is Boolean -> value
        else -> default
    }

private fun Map<String, Any>.optionalString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

internal fun Map<String, Any>.optionalPositiveInt(key: String): Int? {
    val parsed = parseOptionalInt(key)
    return parsed?.takeIf { it > 0 }
}

internal fun Map<String, Any>.optionalNonNegativeInt(key: String): Int? {
    val parsed = parseOptionalInt(key)
    return parsed?.takeIf { it >= 0 }
}

private fun Map<String, Any>.parseOptionalInt(key: String): Int? =
    when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
