package com.example.gradle.mcp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.RoutingContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * Serves MCP over Streamable HTTP until the JVM exits or the engine is stopped.
 *
 * Every client session gets its own [Server] from [newServer]; all sessions share the
 * Gradle runtime captured by the factory (connection pool and build registry are
 * process-wide, matching stdio mode). Per-session state such as the session's
 * default project lives in the SessionProjectContext created inside [newServer].
 *
 * The SSE heartbeat keeps the standalone GET stream (the only channel for
 * server-initiated notifications in JSON-response mode) alive through proxies
 * and idle timeouts.
 *
 * Known upstream limitation (kotlin-sdk 0.15.0): `mcpStreamableHttp` creates the
 * transport and calls `newServer` before validating that a POST is an
 * initialize request, so repeated session-id-less POSTs that are rejected with
 * 400 leave unclosed Server/session objects behind until the process ends.
 */
internal fun serveStreamableHttp(
    endpoint: HttpEndpoint,
    newServer: (RoutingContext) -> Server,
    shutdownRuntime: suspend () -> Unit,
) {
    val engine = embeddedServer(CIO, port = endpoint.port, host = endpoint.host) {
        mcpStreamableHttp(
            path = endpoint.path,
            allowedHosts = endpoint.allowedHosts,
            allowedOrigins = endpoint.allowedOrigins,
            sseHeartbeatConfig = { period = 15.seconds },
        ) {
            newServer(this)
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
