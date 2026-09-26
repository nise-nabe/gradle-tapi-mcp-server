package com.example.gradle.mcp

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.registerBuildTools
import com.example.gradle.mcp.cache.registerCacheTools
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.registerConnectionTools
import com.example.gradle.mcp.connection.registerJavaRuntimeTools
import com.example.gradle.mcp.dependency.registerDependencySourceTools
import com.example.gradle.mcp.model.registerModelTools
import com.example.gradle.mcp.model.resolution.registerDependencyResolutionTools
import com.example.gradle.mcp.protocol.QueryStrippingPathSegmentMatcher
import com.example.gradle.mcp.protocol.registerGradleTapiResources
import com.example.gradle.mcp.server.EofSignalingInputStream
import com.example.gradle.mcp.server.McpTransport
import com.example.gradle.mcp.server.ServerCliOptions
import com.example.gradle.mcp.server.captureProtocolStdout
import com.example.gradle.mcp.server.serveStreamableHttp
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlin.time.Duration.Companion.minutes
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

fun runGradleTapiMcpServer(options: ServerCliOptions = ServerCliOptions()) {
    val connectionManager = GradleConnectionManager()
    val buildExecutionManager = BuildExecutionManager(connectionManager)
    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)

    val shutdownOnce = AtomicBoolean(false)
    suspend fun shutdownRuntime() {
        if (!shutdownOnce.compareAndSet(false, true)) {
            return
        }
        runCatching { buildExecutionManager.shutdown() }
        runCatching { runtime.shutdownDependencySources() }
        runCatching { connectionManager.disconnectAll() }
        runCatching { serverScope.cancel() }
    }

    when (options.transport) {
        McpTransport.STDIO -> {
            // Capture the real stdout for JSON-RPC before any Tooling API
            // call: the Gradle distribution installer writes progress to
            // System.out, which is redirected to stderr from here on.
            val protocolOut = captureProtocolStdout()
            // Detached: a first-time distribution download must not delay
            // the transport startup (initialize would be unanswered).
            serverScope.launch(Dispatchers.IO) {
                connectionManager.tryAutoConnectFromEnvironment()
            }
            serveStdio(runtime, serverScope, ::shutdownRuntime, protocolOut)
        }
        McpTransport.STREAMABLE_HTTP -> {
            serverScope.launch(Dispatchers.IO) {
                connectionManager.tryAutoConnectFromEnvironment()
            }
            serveStreamableHttp(
                endpoint = options.http,
                newServer = { createGradleTapiServer(runtime, serverScope) },
                shutdownRuntime = ::shutdownRuntime,
            )
        }
    }
}

internal fun createGradleTapiServer(runtime: GradleMcpRuntime, serverScope: CoroutineScope): Server {
    val server = Server(
        serverInfo = Implementation(
            name = "gradle-tapi-mcp-server",
            version = mcpServerVersion(),
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(),
                logging = ServerCapabilities.Logging,
                resources = ServerCapabilities.Resources(
                    subscribe = false,
                    listChanged = false,
                ),
            ),
            resourceTemplateMatcherFactory = QueryStrippingPathSegmentMatcher.factory,
        ).apply {
            timeout = 30.minutes
        },
    )

    with(runtime) {
        server.registerConnectionTools(serverScope)
        server.registerJavaRuntimeTools(serverScope)
        server.registerCacheTools(serverScope)
        server.registerModelTools(serverScope)
        server.registerDependencyResolutionTools(serverScope)
        server.registerBuildTools(serverScope)
        server.registerDependencySourceTools(serverScope)
        server.registerGradleTapiResources()
    }
    return server
}

private fun serveStdio(
    runtime: GradleMcpRuntime,
    serverScope: CoroutineScope,
    shutdownRuntime: suspend () -> Unit,
    protocolOut: PrintStream,
) {
    val transportClosed = CountDownLatch(1)
    val server = createGradleTapiServer(runtime, serverScope)
    val transport = StdioServerTransport(
        input = EofSignalingInputStream(System.`in`, transportClosed).asInput(),
        output = protocolOut.asSink().buffered(),
    ) {
        scope = serverScope
        ioDispatcher = Dispatchers.IO
    }

    suspend fun shutdown() {
        runCatching { server.close() }
        transportClosed.countDown()
        shutdownRuntime()
    }

    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { shutdown() } })

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        try {
            transportClosed.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            shutdown()
            awaitSessionClose(done, SESSION_CLOSE_TIMEOUT_MS)
        }
    }
}

/**
 * Waits for the session's onClose callback so transport teardown can finish,
 * bounded so a session that never fires onClose cannot hang the JVM forever.
 */
internal suspend fun awaitSessionClose(done: CompletableJob, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        done.join()
        true
    } ?: false

private const val SESSION_CLOSE_TIMEOUT_MS = 10_000L

private fun mcpServerVersion(): String =
    GradleTapiMcpServerLauncher::class.java.`package`?.implementationVersion ?: "dev"
