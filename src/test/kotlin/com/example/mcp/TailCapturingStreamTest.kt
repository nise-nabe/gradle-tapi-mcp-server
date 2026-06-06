package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TailCapturingStreamTest {
    @Test
    fun `retains only the tail of captured output`() {
        val stream = TailCapturingStream(maxRetainedChars = 8)

        stream.append("0123456789".toByteArray(StandardCharsets.UTF_8), 0, 10)

        val snapshot = stream.snapshot()
        assertEquals("23456789", snapshot.text)
        assertEquals(10, snapshot.totalChars)
    }

    @Test
    fun `supports concurrent writes and reads`() {
        val stream = TailCapturingStream(maxRetainedChars = 256)
        val pool = Executors.newFixedThreadPool(4)

        val futures = (1..100).map {
            pool.submit {
                stream.append("x\n".toByteArray(StandardCharsets.UTF_8), 0, 2)
                stream.snapshot()
            }
        }

        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        futures.forEach { it.get() }

        val snapshot = stream.snapshot()
        assertEquals(200, snapshot.totalChars)
        assertTrue(snapshot.text.endsWith("x\n"))
    }
}
