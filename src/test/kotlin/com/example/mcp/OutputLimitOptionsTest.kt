package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputLimitOptionsTest {
    @Test
    fun `fromArgs uses defaults when omitted`() {
        val options = OutputLimitOptions.fromArgs(emptyMap())

        assertEquals(OutputLimitOptions.DEFAULT_MAX_OUTPUT_CHARS, options.maxOutputChars)
        assertTrue(options.tailOutput)
    }

    @Test
    fun `fromArgs parses custom limits`() {
        val options = OutputLimitOptions.fromArgs(
            mapOf(
                "maxOutputChars" to 1200,
                "tailOutput" to false,
            ),
        )

        assertEquals(1200, options.maxOutputChars)
        assertFalse(options.tailOutput)
    }
}
