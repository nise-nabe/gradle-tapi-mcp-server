package com.example.gradle.mcp.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        val text = "0123456789abcdefghijklmnopqrstuvwxyz0123456789"
        val limited = OutputLimiter.limit(
            text,
            OutputLimitOptions(maxOutputChars = 40, tailOutput = true),
        )

        assertTrue(limited.truncated)
        assertEquals(text.length, limited.totalChars)
        assertTrue(limited.text.length <= 40)
        assertTrue(limited.text.startsWith("... [truncated "))
    }

    @Test
    fun `output limiter omits prefix when limit is too small`() {
        val limited = OutputLimiter.limit(
            "0123456789abcdef",
            OutputLimitOptions(maxOutputChars = 8, tailOutput = true),
        )

        assertEquals("89abcdef", limited.text)
        assertTrue(limited.truncated)
        assertEquals(8, limited.text.length)
    }

    @Test
    fun `output limiter normalizes CRLF`() {
        val limited = OutputLimiter.limit("a\r\nb", OutputLimitOptions(maxOutputChars = 10))

        assertEquals("a\nb", limited.text)
        assertFalse(limited.truncated)
    }

    @Test
    fun `output limiter exposes flat response fields`() {
        val text = "0123456789abcdefghijklmnopqrstuvwxyz0123456789"
        val fields = OutputLimiter.limitFields(
            text,
            OutputLimitOptions(maxOutputChars = 40, tailOutput = true),
            "stdout",
        )

        assertTrue((fields["stdout"] as String).length <= 40)
        assertEquals(true, fields["stdoutTruncated"])
        assertEquals(text.length, fields["stdoutTotalChars"])
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

    @Test
    fun `project tree limits cap visible children and annotate truncation`() {
        val childLimit = ProjectTreeLimits.applyChildLimit(totalChildren = 3, maxChildren = 2)

        assertEquals(2, childLimit.visibleChildCount)
        assertTrue(childLimit.truncated)
        assertEquals(3, childLimit.totalChildCount)
    }

    @Test
    fun `project tree limits omit descendants when max depth reached`() {
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 1, maxDepth = 1, childCount = 1)

        assertTrue(depthLimit.omitChildren)
        assertTrue(depthLimit.truncated)
        assertEquals(1, depthLimit.totalChildCount)
    }

    @Test
    fun `project tree limits omit all children at root-only depth`() {
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 0, maxDepth = 0, childCount = 2)

        assertTrue(depthLimit.omitChildren)
        assertTrue(depthLimit.truncated)
        assertEquals(2, depthLimit.totalChildCount)
    }

    @Test
    fun `project tree limits leave full tree when no caps configured`() {
        val childLimit = ProjectTreeLimits.applyChildLimit(totalChildren = 3, maxChildren = null)
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 0, maxDepth = null, childCount = 3)

        assertEquals(3, childLimit.visibleChildCount)
        assertFalse(childLimit.truncated)
        assertNull(childLimit.totalChildCount)
        assertFalse(depthLimit.omitChildren)
    }
}
