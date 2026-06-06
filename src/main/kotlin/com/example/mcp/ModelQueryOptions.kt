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

        val excerpt = if (options.tailOutput) {
            normalized.takeLast(options.maxOutputChars)
        } else {
            normalized.take(options.maxOutputChars)
        }

        val omittedChars = normalized.length - excerpt.length
        val prefix = "... [truncated $omittedChars chars] ...\n"
        return LimitedText(
            text = prefix + excerpt,
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

private fun Map<String, Any>.optionalPositiveInt(key: String): Int? {
    val parsed = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
    return parsed?.takeIf { it > 0 }
}
