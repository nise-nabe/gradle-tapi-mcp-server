package com.example.gradle.mcp.model

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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

        serialized.shouldBeEmpty()
    }

    @Test
    fun `serializeTasks returns slim task fields by default`() {
        val serialized = ModelSerializers.serializeTasks(
            tasks,
            ModelQueryOptions(includeTasks = true),
        )

        serialized.size shouldBe 3
        serialized[1] shouldBe mapOf("name" to "build", "path" to ":build", "group" to "build")
    }

    @Test
    fun `serializeTasks can include task details`() {
        val serialized = ModelSerializers.serializeTasks(
            tasks,
            ModelQueryOptions(includeTasks = true, includeTaskDetails = true),
        )

        serialized[1]["description"] shouldBe "Build project"
        serialized[1]["displayName"] shouldBe "task ':build'"
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

        filtered.map { it.name } shouldBe listOf("help")
    }

    @Test
    fun `output limiter keeps short text unchanged`() {
        val limited = OutputLimiter.limit("ok", OutputLimitOptions(maxOutputChars = 10, tailOutput = true))

        limited.text shouldBe "ok"
        limited.truncated.shouldBeFalse()
    }

    @Test
    fun `output limiter truncates to tail by default`() {
        val text = "0123456789abcdefghijklmnopqrstuvwxyz0123456789"
        val limited = OutputLimiter.limit(
            text,
            OutputLimitOptions(maxOutputChars = 40, tailOutput = true),
        )

        limited.truncated.shouldBeTrue()
        limited.totalChars shouldBe text.length
        limited.text.length shouldBeLessThanOrEqual 40
        limited.text shouldStartWith "... [truncated "
    }

    @Test
    fun `output limiter omits prefix when limit is too small`() {
        val limited = OutputLimiter.limit(
            "0123456789abcdef",
            OutputLimitOptions(maxOutputChars = 8, tailOutput = true),
        )

        limited.text shouldBe "89abcdef"
        limited.truncated.shouldBeTrue()
        limited.text.length shouldBe 8
    }

    @Test
    fun `output limiter normalizes CRLF`() {
        val limited = OutputLimiter.limit("a\r\nb", OutputLimitOptions(maxOutputChars = 10))

        limited.text shouldBe "a\nb"
        limited.truncated.shouldBeFalse()
    }

    @Test
    fun `output limiter exposes flat response fields`() {
        val text = "0123456789abcdefghijklmnopqrstuvwxyz0123456789"
        val fields = OutputLimiter.limitFields(
            text,
            OutputLimitOptions(maxOutputChars = 40, tailOutput = true),
            "stdout",
        )

        (fields["stdout"] as String).length shouldBeLessThanOrEqual 40
        fields["stdoutTruncated"] shouldBe true
        fields["stdoutTotalChars"] shouldBe text.length
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

        options.includeTasks.shouldBeTrue()
        options.includeTaskDetails.shouldBeTrue()
        options.includeTaskSelectors.shouldBeTrue()
        options.taskGroup shouldBe "build"
        options.taskNamePrefix shouldBe "co"
        options.maxTasks shouldBe 5
    }

    @Test
    fun `model query options ignore non-positive maxTasks`() {
        val options = ModelQueryOptions.fromArgs(mapOf("maxTasks" to 0))

        options.maxTasks.shouldBeNull()
    }

    @Test
    fun `project tree limits cap visible children and annotate truncation`() {
        val childLimit = ProjectTreeLimits.applyChildLimit(totalChildren = 3, maxChildren = 2)

        childLimit.visibleChildCount shouldBe 2
        childLimit.truncated.shouldBeTrue()
        childLimit.totalChildCount shouldBe 3
    }

    @Test
    fun `project tree limits omit descendants when max depth reached`() {
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 1, maxDepth = 1, childCount = 1)

        depthLimit.omitChildren.shouldBeTrue()
        depthLimit.truncated.shouldBeTrue()
        depthLimit.totalChildCount shouldBe 1
    }

    @Test
    fun `project tree limits omit all children at root-only depth`() {
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 0, maxDepth = 0, childCount = 2)

        depthLimit.omitChildren.shouldBeTrue()
        depthLimit.truncated.shouldBeTrue()
        depthLimit.totalChildCount shouldBe 2
    }

    @Test
    fun `project tree limits leave full tree when no caps configured`() {
        val childLimit = ProjectTreeLimits.applyChildLimit(totalChildren = 3, maxChildren = null)
        val depthLimit = ProjectTreeLimits.applyDepthLimit(depth = 0, maxDepth = null, childCount = 3)

        childLimit.visibleChildCount shouldBe 3
        childLimit.truncated.shouldBeFalse()
        childLimit.totalChildCount.shouldBeNull()
        depthLimit.omitChildren.shouldBeFalse()
    }
}
