package com.example.gradle.mcp

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class GradleTapiMcpServerLauncherSmokeTest {
    @Test
    @EnabledIfSystemProperty(named = "gradle.tapi.mcp.smoke", matches = "true")
    fun `launcher keeps stdout json only on initialize`() {
        val jar = projectJar()
        val process = ProcessBuilder("java", "-jar", jar.absolutePath)
            .directory(projectRoot())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0.0"}}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}

                """.trimIndent(),
            )
            writer.flush()
        }

        val firstLine = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readLine()
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)

        firstLine.shouldNotBeNull()
        firstLine shouldStartWith "{\"jsonrpc\""
        firstLine shouldNotContain "kotlin-logging"
    }

    private fun projectJar() =
        projectRoot()
            .resolve("build/libs/gradle-tapi-mcp-server-0.2.3.jar")
            .also { require(it.isFile) { "Missing ${it.absolutePath}; run ./gradlew jar first" } }

    private fun projectRoot(): File =
        System.getProperty("gradle.tapi.mcp.projectDir")?.let(::File)
            ?: File(System.getProperty("user.dir"))
}
