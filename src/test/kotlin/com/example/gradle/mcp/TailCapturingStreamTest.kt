package com.example.gradle.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `buffers split utf8 bytes without replacement characters`() {
        val stream = TailCapturingStream(maxRetainedChars = 100)
        val bytes = "😀".toByteArray(StandardCharsets.UTF_8)
        stream.append(bytes.copyOfRange(0, 2), 0, 2)
        assertEquals("", stream.snapshot().text)
        stream.append(bytes.copyOfRange(2, 4), 0, 2)
        assertEquals("😀", stream.snapshot().text)
        assertFalse(stream.snapshot().text.contains('\uFFFD'))
    }

    @Test
    fun `trims by code point without replacement characters`() {
        val stream = TailCapturingStream(maxRetainedChars = 2)
        val emoji = "😀😀😀"
        val bytes = emoji.toByteArray(StandardCharsets.UTF_8)
        stream.append(bytes, 0, bytes.size)

        val snapshot = stream.snapshot()
        assertEquals("😀😀", snapshot.text)
        assertFalse(snapshot.text.contains('\uFFFD'))
    }

    @Test
    fun `normalizes CRLF in snapshot`() {
        val stream = TailCapturingStream(maxRetainedChars = 32)
        stream.append("a\r\nb".toByteArray(StandardCharsets.UTF_8), 0, 4)

        assertEquals("a\nb", stream.snapshot().text)
        assertEquals(3, stream.snapshot().totalChars)
    }

    @Test
    fun `joins split CRLF across appends`() {
        val stream = TailCapturingStream(maxRetainedChars = 32)
        stream.append("a\r".toByteArray(StandardCharsets.UTF_8), 0, 2)
        stream.append("\nb".toByteArray(StandardCharsets.UTF_8), 0, 2)

        assertEquals("a\nb", stream.snapshot().text)
        assertEquals(3, stream.snapshot().totalChars)
    }

    @Test
    fun `decodes two byte utf8 sequences`() {
        val stream = TailCapturingStream(maxRetainedChars = 32)
        val bytes = "Я".toByteArray(StandardCharsets.UTF_8)
        stream.append(bytes, 0, bytes.size)

        assertEquals("Я", stream.snapshot().text)
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
