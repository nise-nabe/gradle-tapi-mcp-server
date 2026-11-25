package com.example.gradle.mcp.protocol

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClientConnectionBuildNotifierTest {
    private data class Harness(
        val notifier: ClientConnectionBuildNotifier,
        val received: MutableList<String>,
        val scope: CoroutineScope,
    )

    private fun notifierHarness(gate: CountDownLatch? = null): Harness {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val connection = Proxy.newProxyInstance(
            ClientConnection::class.java.classLoader,
            arrayOf(ClientConnection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSessionId" -> "test-session"
                "notification" -> {
                    gate?.await()
                    received += "progress"
                    Unit
                }
                "sendLoggingMessage" -> {
                    gate?.await()
                    received += "log"
                    Unit
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ClientConnection
        val scope = CoroutineScope(SupervisorJob())
        return Harness(
            ClientConnectionBuildNotifier(scope, connection, ProgressToken("tok")),
            received,
            scope,
        )
    }

    private fun awaitCount(received: List<String>, expected: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (received.size < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `delivers progress and log notifications in launch order`() {
        val harness = notifierHarness()
        try {
            harness.notifier.notifyProgress(0.0, 1.0, "start")
            harness.notifier.notifyLog("line-1", LoggingLevel.Info)
            harness.notifier.notifyProgress(0.5, 1.0, "half")
            harness.notifier.notifyLog("line-2", LoggingLevel.Info)
            harness.notifier.notifyProgress(1.0, 1.0, "done")
            awaitCount(harness.received, 5)
            harness.received shouldBe listOf("progress", "log", "progress", "log", "progress")
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun `progress notifications drop silently when the backlog is full`() {
        val gate = CountDownLatch(1)
        val harness = notifierHarness(gate)
        try {
            val sent = ClientConnectionBuildNotifier.MAX_PENDING_NOTIFICATIONS + 10
            repeat(sent) { harness.notifier.notifyProgress(it.toDouble(), sent.toDouble(), "p$it") }
            // Only the bounded backlog can be queued while the client is stuck.
            gate.countDown()
            awaitCount(harness.received, ClientConnectionBuildNotifier.MAX_PENDING_NOTIFICATIONS)
            harness.received shouldHaveSize ClientConnectionBuildNotifier.MAX_PENDING_NOTIFICATIONS
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun `log notifications apply backpressure instead of dropping`() {
        val gate = CountDownLatch(1)
        val harness = notifierHarness(gate)
        val overflowReturned = CountDownLatch(1)
        try {
            repeat(ClientConnectionBuildNotifier.MAX_PENDING_NOTIFICATIONS) {
                harness.notifier.notifyLog("seed-$it", LoggingLevel.Info)
            }
            val overflow = Thread {
                harness.notifier.notifyLog("overflow", LoggingLevel.Info)
                overflowReturned.countDown()
            }
            overflow.start()
            // The extra log call must still be blocked while the client is stuck.
            Thread.sleep(300)
            overflowReturned.count shouldBe 1L
            gate.countDown()
            overflowReturned.await(10, TimeUnit.SECONDS) shouldBe true
            overflow.join(10_000)
            awaitCount(harness.received, ClientConnectionBuildNotifier.MAX_PENDING_NOTIFICATIONS + 1)
            harness.received shouldHaveSize ClientConnectionBuildNotifier.MAX_PENDING_NOTIFICATIONS + 1
        } finally {
            gate.countDown()
            harness.scope.cancel()
        }
    }
}
