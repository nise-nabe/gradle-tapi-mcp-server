package com.example.gradle.mcp

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.buildTools
import com.example.gradle.mcp.cache.cacheTools
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.connectionTools
import com.example.gradle.mcp.connection.javaRuntimeTools
import com.example.gradle.mcp.model.modelTools
import com.example.gradle.mcp.protocol.mcpJsonMapper
import com.example.gradle.mcp.server.EofSignalingInputStream
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val connectionManager = GradleConnectionManager()
    val buildExecutionManager = BuildExecutionManager(connectionManager)
    connectionManager.tryAutoConnectFromEnvironment()

    val transportClosed = CountDownLatch(1)
    val transport = StdioServerTransportProvider(
        mcpJsonMapper,
        EofSignalingInputStream(System.`in`, transportClosed),
        System.out,
    )
    val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)
    val server = context(runtime) {
        McpServer.sync(transport)
            .serverInfo("gradle-tapi-mcp-server", "0.1.0")
            .requestTimeout(Duration.ofMinutes(30))
            .capabilities(
                McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .logging()
                    .build()
            )
            .tools(
                connectionTools() +
                    javaRuntimeTools() +
                    cacheTools() +
                    modelTools() +
                    buildTools()
            )
            .build()
    }

    val shutdownOnce = AtomicBoolean(false)
    fun shutdownBestEffort() {
        if (!shutdownOnce.compareAndSet(false, true)) {
            return
        }
        runCatching { buildExecutionManager.shutdown() }
        runCatching { connectionManager.disconnectAll() }
        runCatching { server.closeGracefully() }
        transportClosed.countDown()
    }

    Runtime.getRuntime().addShutdownHook(Thread { shutdownBestEffort() })

    try {
        transportClosed.await()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } finally {
        shutdownBestEffort()
    }
}
