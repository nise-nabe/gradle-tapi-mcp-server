package com.example.gradle.mcp

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.SessionProjectContext
import com.example.gradle.mcp.support.noopProjectConnection
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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

    @Test
    fun `server session close releases ambient project holders`(@TempDir project: File) {
        runBlocking {
            val connectionManager = GradleConnectionManager()
            connectionManager.seedConnectionForTests(noopProjectConnection(), project)
            val sessionContext = SessionProjectContext(
                workspaceProject = null,
                seedProjectDirectories = listOf(project),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = createGradleTapiServer(
                    runtime = DefaultGradleMcpRuntime(
                        connectionManager,
                        BuildExecutionManager(connectionManager),
                    ),
                    serverScope = scope,
                    session = sessionContext,
                )
                val serverSession = server.createSession(ClosingTransport())

                connectionManager.isConnected(project).shouldBeTrue()
                serverSession.close()

                connectionManager.isConnected(project).shouldBeFalse()
            } finally {
                scope.cancel()
            }
        }
    }

    private class ClosingTransport : Transport {
        private val closeCallbacks = CopyOnWriteArrayList<() -> Unit>()

        override suspend fun start() = Unit

        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) = Unit

        override suspend fun close() {
            closeCallbacks.forEach { it() }
        }

        override fun onClose(block: () -> Unit) {
            closeCallbacks += block
        }

        override fun onError(block: (Throwable) -> Unit) = Unit

        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) = Unit
    }
}
