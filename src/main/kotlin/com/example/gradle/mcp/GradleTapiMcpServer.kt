package com.example.gradle.mcp

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.registerBuildTools
import com.example.gradle.mcp.cache.registerCacheTools
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.registerConnectionTools
import com.example.gradle.mcp.connection.registerJavaRuntimeTools
import com.example.gradle.mcp.model.registerModelTools
import com.example.gradle.mcp.server.EofSignalingInputStream
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlin.time.Duration.Companion.minutes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

fun runGradleTapiMcpServer() {
    val connectionManager = GradleConnectionManager()
    val buildExecutionManager = BuildExecutionManager(connectionManager)
    connectionManager.tryAutoConnectFromEnvironment()

    val transportClosed = CountDownLatch(1)
    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)
    val server = Server(
        serverInfo = Implementation(
            name = "gradle-tapi-mcp-server",
            version = "0.3.1",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(),
                logging = ServerCapabilities.Logging,
            ),
        ).apply {
            timeout = 30.minutes
        },
    )

    with(runtime) {
        server.registerConnectionTools(serverScope)
        server.registerJavaRuntimeTools(serverScope)
        server.registerCacheTools(serverScope)
        server.registerModelTools(serverScope)
        server.registerBuildTools(serverScope)
    }

    val transport = StdioServerTransport(
        inputStream = EofSignalingInputStream(System.`in`, transportClosed).asInput(),
        outputStream = System.out.asSink().buffered(),
    )

    val shutdownOnce = AtomicBoolean(false)
    suspend fun shutdownBestEffort() {
        if (!shutdownOnce.compareAndSet(false, true)) {
            return
        }
        runCatching { buildExecutionManager.shutdown() }
        runCatching { connectionManager.disconnectAll() }
        runCatching { server.close() }
        runCatching { serverScope.cancel() }
        transportClosed.countDown()
    }

    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { shutdownBestEffort() } })

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        try {
            transportClosed.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            shutdownBestEffort()
            done.join()
        }
    }
}
