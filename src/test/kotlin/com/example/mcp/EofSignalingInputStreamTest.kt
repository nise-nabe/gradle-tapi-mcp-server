package com.example.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EofSignalingInputStreamTest {
    @Test
    fun `EOF on single-byte read signals transport closed once`() {
        val latch = CountDownLatch(1)
        val stream = EofSignalingInputStream(ByteArrayInputStream(ByteArray(0)), latch)

        assertEquals(-1, stream.read())
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS))
        stream.close()
        assertEquals(0, latch.count)
    }

    @Test
    fun `EOF on buffered read signals transport closed once`() {
        val latch = CountDownLatch(1)
        val stream = EofSignalingInputStream(ByteArrayInputStream(ByteArray(0)), latch)
        val buffer = ByteArray(8)

        assertEquals(-1, stream.read(buffer, 0, buffer.size))
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS))
        stream.close()
        assertEquals(0, latch.count)
    }

    @Test
    fun `close signals transport closed`() {
        val latch = CountDownLatch(1)
        val stream = EofSignalingInputStream(ByteArrayInputStream("x".toByteArray()), latch)

        stream.close()

        assertTrue(latch.await(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `close signals transport closed even when delegate close throws`() {
        val latch = CountDownLatch(1)
        val throwingDelegate = object : InputStream() {
            override fun read(): Int = -1

            override fun close() {
                throw RuntimeException("close failed")
            }
        }
        val stream = EofSignalingInputStream(throwingDelegate, latch)

        assertThrows(RuntimeException::class.java) { stream.close() }
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS))
    }
}
