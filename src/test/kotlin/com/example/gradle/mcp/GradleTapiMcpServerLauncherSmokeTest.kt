package com.example.gradle.mcp

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
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
        firstLine shouldNotContain "kotlin-logging"
    }

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
