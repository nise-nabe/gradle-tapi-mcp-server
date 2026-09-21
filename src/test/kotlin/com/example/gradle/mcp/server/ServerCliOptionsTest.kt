package com.example.gradle.mcp.server

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ServerCliOptionsTest {
    @Test
    fun `defaults to stdio`() {
        val options = ServerCliOptions.parse(emptyArray())

        options.transport shouldBe McpTransport.STDIO
        options.showHelp shouldBe false
        options.http shouldBe HttpEndpoint()
    }

    @Test
    fun `parses streamable http transport and http alias`() {
        ServerCliOptions.parse(arrayOf("--transport=streamable-http")).transport shouldBe
            McpTransport.STREAMABLE_HTTP
        ServerCliOptions.parse(arrayOf("--transport=http")).transport shouldBe
            McpTransport.STREAMABLE_HTTP
        ServerCliOptions.parse(arrayOf("--transport=stdio")).transport shouldBe McpTransport.STDIO
    }

    @Test
    fun `parses http endpoint options`() {
        val options = ServerCliOptions.parse(
            arrayOf(
                "--transport=streamable-http",
                "--host=0.0.0.0",
                "--port=9000",
                "--path=/mcp/v1",
            ),
        )

        options.http shouldBe HttpEndpoint(host = "0.0.0.0", port = 9000, path = "/mcp/v1")
    }

    @Test
    fun `parses allowed hosts and origins as comma separated lists`() {
        val options = ServerCliOptions.parse(
            arrayOf(
                "--transport=streamable-http",
                "--allowed-hosts=example.com, mcp.example.com",
                "--allowed-origins=example.com",
            ),
        )

        options.http.allowedHosts shouldBe listOf("example.com", "mcp.example.com")
        options.http.allowedOrigins shouldBe listOf("example.com")
    }

    @Test
    fun `help flag sets showHelp`() {
        ServerCliOptions.parse(arrayOf("--help")).showHelp shouldBe true
        ServerCliOptions.parse(arrayOf("-h")).showHelp shouldBe true
    }

    @Test
    fun `rejects unknown options`() {
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--bogus"))
        }
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("positional"))
        }
    }

    @Test
    fun `rejects unknown transport`() {
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--transport=sse"))
        }
    }

    @Test
    fun `rejects option without value`() {
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--transport"))
        }
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--host="))
        }
    }

    @Test
    fun `rejects invalid ports`() {
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--port=abc"))
        }
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--port=0"))
        }
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--port=70000"))
        }
    }

    @Test
    fun `rejects path without leading slash`() {
        shouldThrow<IllegalArgumentException> {
            ServerCliOptions.parse(arrayOf("--path=mcp"))
        }
    }
}
