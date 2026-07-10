package com.example.gradle.mcp.model

internal class TaskFilterBudget(private val maxTasks: Int?) {
    private var remaining: Int? = maxTasks
    private var totalMatched: Int = 0
    private var totalEmitted: Int = 0

    fun <T> takeAndSerialize(items: List<T>, serialize: (T) -> Map<String, Any?>): List<Map<String, Any?>> {
        totalMatched += items.size
        val max = remaining
        val taken = when {
            max == null -> items
            max <= 0 -> emptyList()
            else -> items.take(max).also { remaining = max - it.size }
        }
        val serialized = taken.map(serialize)
        totalEmitted += serialized.size
        return serialized
    }

    fun rootMetadata(): Map<String, Any?> =
        if (maxTasks != null && totalMatched > totalEmitted) {
            mapOf(
                "tasksTruncated" to true,
                "tasksTotalMatched" to totalMatched,
            )
        } else {
            emptyMap()
        }
}
