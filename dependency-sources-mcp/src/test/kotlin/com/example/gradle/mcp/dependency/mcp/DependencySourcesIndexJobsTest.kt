package com.example.gradle.mcp.dependency.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DependencySourcesIndexJobsTest {
    @Test
    fun `saturation rejects with queue full exception`() {
        val jobs = DependencySourcesIndexJobs(maxConcurrentJobs = 1, maxQueuedJobs = 1)
        val blocker = CountDownLatch(1)
        val running = CountDownLatch(1)
        try {
            val occupied = File(System.getProperty("java.io.tmpdir"), "idx-occupied-${System.nanoTime()}")
            val queued = File(System.getProperty("java.io.tmpdir"), "idx-queued-${System.nanoTime()}")
            val rejected = File(System.getProperty("java.io.tmpdir"), "idx-rejected-${System.nanoTime()}")
            jobs.start(occupied, "full", null) {
                running.countDown()
                blocker.await()
                emptyMap()
            }
            running.await(5, TimeUnit.SECONDS) shouldBe true
            jobs.start(queued, "full", null) {
                blocker.await()
                emptyMap()
            }
            val exception = shouldThrow<DependencySourcesIndexQueueFullException> {
                jobs.start(rejected, "full", null) { emptyMap() }
            }
            exception.message shouldContain "queue is full"
        } finally {
            blocker.countDown()
            jobs.shutdown()
        }
    }

    @Test
    fun `finished jobs free capacity for later runs`() {
        val jobs = DependencySourcesIndexJobs(maxConcurrentJobs = 1, maxQueuedJobs = 1)
        try {
            val first = File(System.getProperty("java.io.tmpdir"), "idx-first-${System.nanoTime()}")
            val second = File(System.getProperty("java.io.tmpdir"), "idx-second-${System.nanoTime()}")
            val firstJob = jobs.start(first, "full", null) { emptyMap() }
            firstJob.await(10_000L) shouldBe true
            val secondJob = jobs.start(second, "full", null) { emptyMap() }
            secondJob.await(10_000L) shouldBe true
            secondJob.status() shouldBe IndexJob.STATUS_SUCCEEDED
        } finally {
            jobs.shutdown()
        }
    }
}
