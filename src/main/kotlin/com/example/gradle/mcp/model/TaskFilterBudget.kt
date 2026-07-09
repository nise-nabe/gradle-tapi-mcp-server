package com.example.gradle.mcp.model

internal class TaskFilterBudget(private val options: ModelQueryOptions) {
    private var remaining: Int? = options.maxTasks
    private var totalMatched: Int = 0
    private var totalEmitted: Int = 0

    fun filterAndSerialize(tasks: List<TaskSnapshot>): List<Map<String, Any?>> {
        if (!options.includeTasks) {
            return emptyList()
        }

        val filtered = ModelSerializers.filterTasksWithoutLimit(tasks, options)
        totalMatched += filtered.size

        val max = remaining
        if (max == null) {
            val serialized = filtered.map { ModelSerializers.serializeTaskSnapshot(it, options.includeTaskDetails) }
            totalEmitted += serialized.size
            return serialized
        }
        if (max <= 0) {
            return emptyList()
        }

        val taken = filtered.take(max)
        remaining = max - taken.size
        val serialized = taken.map { ModelSerializers.serializeTaskSnapshot(it, options.includeTaskDetails) }
        totalEmitted += serialized.size
        return serialized
    }

    fun rootMetadata(): Map<String, Any?> =
        if (options.maxTasks != null && totalMatched > totalEmitted) {
            mapOf(
                "tasksTruncated" to true,
                "tasksTotalMatched" to totalMatched,
            )
        } else {
            emptyMap()
        }
}
