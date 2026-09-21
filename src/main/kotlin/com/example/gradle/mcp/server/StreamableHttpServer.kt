package com.example.gradle.mcp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.runBlocking

/**
 * Serves MCP over Streamable HTTP until the JVM exits or the engine is stopped.
 *
 * Every client session gets its own [Server] from [newServer]; all sessions share the
 * Gradle runtime captured by the factory (connection pool and build registry are
 * process-wide, matching stdio mode).
 */
internal fun serveStreamableHttp(
    endpoint: HttpEndpoint,
    newServer: () -> Server,
    shutdownRuntime: suspend () -> Unit,
) {
    val engine = embeddedServer(CIO, port = endpoint.port, host = endpoint.host) {
        mcpStreamableHttp(
            path = endpoint.path,
            allowedHosts = endpoint.allowedHosts,
            allowedOrigins = endpoint.allowedOrigins,
        ) {
            newServer()
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { shutdownRuntime() } })
    try {
        System.err.println(
            "gradle-tapi-mcp-server serving MCP streamable-http on " +
                "http://${endpoint.host}:${endpoint.port}${endpoint.path}",
        )
        engine.start(wait = true)
    } finally {
        runBlocking { shutdownRuntime() }
    }
}
