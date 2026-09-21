package com.example.gradle.mcp

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class GradleTapiMcpServerLauncherSmokeTest {
    @Test
    fun `launcher keeps stdout json only on initialize`() {
        val jar = projectJar()
        val process = ProcessBuilder("java", "-jar", jar.absolutePath)
            .directory(projectRoot())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        writer.write(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0.0"}}}""",
        )
        writer.write("\n")
        writer.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        writer.write("\n")
        writer.flush()

        val executor = Executors.newSingleThreadExecutor()
        val firstLine = try {
            executor.submit<String?> {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).readLine()
            }.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)

        firstLine.shouldNotBeNull()
        firstLine shouldStartWith "{"
        firstLine shouldContain "\"jsonrpc\""
        firstLine shouldContain "\"resources\""
        firstLine shouldNotContain "kotlin-logging"
    }

    @Test
    fun `launcher serves initialize over streamable http`() {
        val jar = projectJar()
        val port = freePort()
        val process = ProcessBuilder(
            "java", "-jar", jar.absolutePath,
            "--transport=streamable-http",
            "--host=127.0.0.1",
            "--port=$port",
        )
            .directory(projectRoot())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        try {
            val response = postInitializeWithRetry(process, port)
            response.statusCode() shouldBe 200
            response.headers().firstValue("mcp-session-id").isPresent shouldBe true
            response.body() shouldContain "\"jsonrpc\""
            response.body() shouldContain "gradle-tapi-mcp-server"
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun postInitializeWithRetry(process: Process, port: Int): HttpResponse<String> {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0.0"}}}""",
                ),
            )
            .build()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            if (!process.isAlive) {
                fail<Nothing>("MCP server exited with code ${process.exitValue()} before accepting HTTP")
            }
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                if (System.nanoTime() > deadline) {
                    throw e
                }
                Thread.sleep(200)
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun projectJar(): File {
        val libsDir = projectRoot().resolve("build/libs")
        val jars = libsDir.listFiles { _, name ->
            name.startsWith("gradle-tapi-mcp-server-") &&
                name.endsWith(".jar") &&
                !name.endsWith("-plain.jar")
        }?.toList().orEmpty()
        require(jars.size == 1) {
            "Expected exactly one fat jar in ${libsDir.absolutePath}; run ./gradlew jar first"
        }
        return jars.single()
    }

    private fun projectRoot(): File =
        System.getProperty("gradle.tapi.mcp.projectDir")?.let(::File)
            ?: File(System.getProperty("user.dir"))
}
