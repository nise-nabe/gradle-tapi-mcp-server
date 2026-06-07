package com.example.gradle.mcp.server

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
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

        stream.read() shouldBe -1
        latch.await(100, TimeUnit.MILLISECONDS).shouldBeTrue()
        stream.close()
        latch.count shouldBe 0
    }

    @Test
    fun `EOF on buffered read signals transport closed once`() {
        val latch = CountDownLatch(1)
        val stream = EofSignalingInputStream(ByteArrayInputStream(ByteArray(0)), latch)
        val buffer = ByteArray(8)

        stream.read(buffer, 0, buffer.size) shouldBe -1
        latch.await(100, TimeUnit.MILLISECONDS).shouldBeTrue()
        stream.close()
        latch.count shouldBe 0
    }

    @Test
    fun `close signals transport closed`() {
        val latch = CountDownLatch(1)
        val stream = EofSignalingInputStream(ByteArrayInputStream("x".toByteArray()), latch)

        stream.close()

        latch.await(100, TimeUnit.MILLISECONDS).shouldBeTrue()
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

        shouldThrow<RuntimeException> { stream.close() }
        latch.await(100, TimeUnit.MILLISECONDS).shouldBeTrue()
    }
}
