package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelSerializersTest {
    private val tasks = listOf(
        TaskSnapshot("help", ":help", "Help message", "help", "task ':help'"),
        TaskSnapshot("build", ":build", "Build project", "build", "task ':build'"),
        TaskSnapshot("test", ":test", "Run tests", "verification", "task ':test'"),
    )

    @Test
    fun `serializeTasks omits tasks by default`() {
        val serialized = ModelSerializers.serializeTasks(tasks, ModelQueryOptions())

        assertTrue(serialized.isEmpty())
    }

    @Test
    fun `serializeTasks returns slim task fields by default`() {
        val serialized = ModelSerializers.serializeTasks(
            tasks,
            ModelQueryOptions(includeTasks = true),
        )

        assertEquals(3, serialized.size)
        assertEquals(
            mapOf("name" to "build", "path" to ":build", "group" to "build"),
            serialized[1],
        )
    }

    @Test
    fun `serializeTasks can include task details`() {
        val serialized = ModelSerializers.serializeTasks(
            tasks,
            ModelQueryOptions(includeTasks = true, includeTaskDetails = true),
        )

        assertEquals("Build project", serialized[1]["description"])
        assertEquals("task ':build'", serialized[1]["displayName"])
    }

    @Test
    fun `filterTasks supports group prefix and max limits`() {
        val filtered = ModelSerializers.filterTasks(
            tasks,
            ModelQueryOptions(
                includeTasks = true,
                taskGroup = "help",
                taskNamePrefix = "h",
                maxTasks = 1,
            ),
        )

        assertEquals(listOf("help"), filtered.map { it.name })
    }

    @Test
    fun `output limiter keeps short text unchanged`() {
        val limited = OutputLimiter.limit("ok", OutputLimitOptions(maxOutputChars = 10, tailOutput = true))

        assertEquals("ok", limited.text)
        assertFalse(limited.truncated)
    }

    @Test
    fun `output limiter truncates to tail by default`() {
        val limited = OutputLimiter.limit(
            "0123456789abcdef",
            OutputLimitOptions(maxOutputChars = 8, tailOutput = true),
        )

        assertEquals("89abcdef", limited.text)
        assertTrue(limited.truncated)
        assertEquals(16, limited.totalChars)
    }

    @Test
    fun `output limiter exposes flat response fields`() {
        val fields = OutputLimiter.limitFields(
            "0123456789abcdef",
            OutputLimitOptions(maxOutputChars = 8, tailOutput = true),
            "stdout",
        )

        assertEquals("89abcdef", fields["stdout"])
        assertEquals(true, fields["stdoutTruncated"])
        assertEquals(16, fields["stdoutTotalChars"])
    }

    @Test
    fun `model query options parse booleans and limits from args`() {
        val options = ModelQueryOptions.fromArgs(
            mapOf(
                "includeTasks" to true,
                "includeTaskDetails" to true,
                "includeTaskSelectors" to true,
                "taskGroup" to "build",
                "taskNamePrefix" to "co",
                "maxTasks" to 5,
            ),
        )

        assertTrue(options.includeTasks)
        assertTrue(options.includeTaskDetails)
        assertTrue(options.includeTaskSelectors)
        assertEquals("build", options.taskGroup)
        assertEquals("co", options.taskNamePrefix)
        assertEquals(5, options.maxTasks)
    }

    @Test
    fun `model query options ignore non-positive maxTasks`() {
        val options = ModelQueryOptions.fromArgs(mapOf("maxTasks" to 0))

        assertEquals(null, options.maxTasks)
    }
}
