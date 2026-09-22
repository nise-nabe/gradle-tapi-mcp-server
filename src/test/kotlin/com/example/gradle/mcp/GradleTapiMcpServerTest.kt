package com.example.gradle.mcp

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class GradleTapiMcpServerTest {
    @Test
    fun `awaitSessionClose returns once the session job completes`() {
        runBlocking {
            val done = Job()
            done.complete()
            awaitSessionClose(done, 10_000).shouldBeTrue()
        }
    }

    @Test
    fun `awaitSessionClose times out instead of joining forever`() {
        runBlocking {
            val done = Job()
            val startedAt = System.currentTimeMillis()
            awaitSessionClose(done, 200).shouldBeFalse()
            (System.currentTimeMillis() - startedAt).shouldBeLessThan(10_000)
        }
    }
}
