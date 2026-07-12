package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StreamResponseFieldsTest {
    @Test
    fun `delta mode returns only new stdout bytes`() {
        val snapshot = CapturedStreamSnapshot(
            text = "line1\nline2\nline3\n",
            totalChars = 18,
        )

        val firstPoll = streamResponseFields(
            snapshot = snapshot,
            outputLimit = OutputLimitOptions(includeOutput = true, sinceStdoutOffset = 0),
            fieldPrefix = "stdout",
        )
        firstPoll["stdoutOffset"] shouldBe 18
        (firstPoll["stdoutDelta"] as String) shouldBe "line1\nline2\nline3\n"

        val secondPoll = streamResponseFields(
            snapshot = snapshot,
            outputLimit = OutputLimitOptions(includeOutput = true, sinceStdoutOffset = 18),
            fieldPrefix = "stdout",
        )
        secondPoll["stdoutOffset"] shouldBe 18
        secondPoll["stdoutDelta"] shouldBe ""
    }

    @Test
    fun `legacy mode keeps full tail when since offset omitted`() {
        val snapshot = CapturedStreamSnapshot(text = "hello", totalChars = 5)

        val fields = streamResponseFields(
            snapshot = snapshot,
            outputLimit = OutputLimitOptions(includeOutput = true),
            fieldPrefix = "stdout",
        )

        fields["stdout"] shouldBe "hello"
        fields["stdoutTotalChars"] shouldBe 5
        fields.containsKey("stdoutDelta") shouldBe false
    }
}
