package com.example.gradle.mcp.build

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
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
        snapshot.text shouldBe "23456789"
        snapshot.totalChars shouldBe 10
    }

    @Test
    fun `buffers split utf8 bytes without replacement characters`() {
        val stream = TailCapturingStream(maxRetainedChars = 100)
        val bytes = "😀".toByteArray(StandardCharsets.UTF_8)
        stream.append(bytes.copyOfRange(0, 2), 0, 2)
        stream.snapshot().text shouldBe ""
        stream.append(bytes.copyOfRange(2, 4), 0, 2)
        stream.snapshot().text shouldBe "😀"
        stream.snapshot().text shouldNotContain "\uFFFD"
    }

    @Test
    fun `trims by code point without replacement characters`() {
        val stream = TailCapturingStream(maxRetainedChars = 2)
        val emoji = "😀😀😀"
        val bytes = emoji.toByteArray(StandardCharsets.UTF_8)
        stream.append(bytes, 0, bytes.size)

        val snapshot = stream.snapshot()
        snapshot.text shouldBe "😀😀"
        snapshot.text shouldNotContain "\uFFFD"
    }

    @Test
    fun `normalizes CRLF in snapshot`() {
        val stream = TailCapturingStream(maxRetainedChars = 32)
        stream.append("a\r\nb".toByteArray(StandardCharsets.UTF_8), 0, 4)

        stream.snapshot().text shouldBe "a\nb"
        stream.snapshot().totalChars shouldBe 3
    }

    @Test
    fun `joins split CRLF across appends`() {
        val stream = TailCapturingStream(maxRetainedChars = 32)
        stream.append("a\r".toByteArray(StandardCharsets.UTF_8), 0, 2)
        stream.append("\nb".toByteArray(StandardCharsets.UTF_8), 0, 2)

        stream.snapshot().text shouldBe "a\nb"
        stream.snapshot().totalChars shouldBe 3
    }

    @Test
    fun `decodes two byte utf8 sequences`() {
        val stream = TailCapturingStream(maxRetainedChars = 32)
        val bytes = "Я".toByteArray(StandardCharsets.UTF_8)
        stream.append(bytes, 0, bytes.size)

        stream.snapshot().text shouldBe "Я"
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
        pool.awaitTermination(10, TimeUnit.SECONDS).shouldBeTrue()
        futures.forEach { it.get() }

        val snapshot = stream.snapshot()
        snapshot.totalChars shouldBe 200
        snapshot.text shouldEndWith "x\n"
    }
}
