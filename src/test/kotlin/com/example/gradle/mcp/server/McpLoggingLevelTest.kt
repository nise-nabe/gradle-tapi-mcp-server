package com.example.gradle.mcp.server

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression coverage for `logging/setLevel`: the Kotlin MCP SDK 0.16.0
 * `ServerSession` registers the handler and `ClientConnection` filters
 * `notifications/message` below the client-selected threshold. These tests pin
 * that behavior so a future SDK bump cannot silently drop it.
 */
class McpLoggingLevelTest {
    private fun newSession(): Pair<ServerSession, RecordingTransport> {
        val transport = RecordingTransport()
        val session = ServerSession(
            serverInfo = Implementation(name = "test-server", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(logging = ServerCapabilities.Logging),
            ),
            instructions = null,
        )
        return session to transport
    }

    private fun logMessage(level: LoggingLevel): LoggingMessageNotification =
        LoggingMessageNotification(
            LoggingMessageNotificationParams(
                level = level,
                logger = "test",
                data = buildJsonObject { put("message", "log-$level") },
            ),
        )

    private fun RecordingTransport.sentLogMessages(): List<JSONRPCNotification> =
        sent.filterIsInstance<JSONRPCNotification>()
            .filter { it.method == "notifications/message" }

    @Test
    fun `logging setLevel suppresses messages below the threshold`() = runBlocking {
        val (session, transport) = newSession()
        session.connect(transport)

        transport.deliver(
            JSONRPCRequest(
                id = "set-level",
                method = "logging/setLevel",
                params = buildJsonObject { put("level", JsonPrimitive("warning")) },
            ),
        )

        session.sendLoggingMessage(logMessage(LoggingLevel.Info))
        session.sendLoggingMessage(logMessage(LoggingLevel.Debug))
        transport.sentLogMessages().shouldBeEmpty()

        session.sendLoggingMessage(logMessage(LoggingLevel.Warning))
        session.sendLoggingMessage(logMessage(LoggingLevel.Error))
        transport.sentLogMessages().shouldHaveSize(2)

        session.close()
    }

    @Test
    fun `logging messages are unfiltered before setLevel`() = runBlocking {
        val (session, transport) = newSession()
        session.connect(transport)

        session.sendLoggingMessage(logMessage(LoggingLevel.Debug))
        session.sendLoggingMessage(logMessage(LoggingLevel.Info))
        transport.sentLogMessages().shouldHaveSize(2)

        session.close()
    }

    private class RecordingTransport : Transport {
        val sent = CopyOnWriteArrayList<JSONRPCMessage>()
        private var messageHandler: (suspend (JSONRPCMessage) -> Unit)? = null

        override suspend fun start() = Unit

        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
            sent += message
        }

        override suspend fun close() = Unit

        override fun onClose(block: () -> Unit) = Unit

        override fun onError(block: (Throwable) -> Unit) = Unit

        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
            messageHandler = block
        }

        suspend fun deliver(message: JSONRPCMessage) {
            messageHandler?.invoke(message)
        }
    }
}
