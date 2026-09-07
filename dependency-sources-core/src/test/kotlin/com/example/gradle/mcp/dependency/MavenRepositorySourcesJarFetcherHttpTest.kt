package com.example.gradle.mcp.dependency

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class MavenRepositorySourcesJarFetcherHttpTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val artifact = DependencyArtifactRef("com.acme", "core", "1.0.0")
    private val artifactPath = "/repository/maven-public/com/acme/core/1.0.0/core-1.0.0-sources.jar"

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/repository/maven-public/"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `downloads zip jar and moves into destination`() {
        val zipBytes = zipBytes("Core.kt", "class Core\n")
        server.createContext(artifactPath) { exchange ->
            exchange.sendResponseHeaders(200, zipBytes.size.toLong())
            exchange.responseBody.use { it.write(zipBytes) }
        }

        val destination = File(tempDir, "out/core-1.0.0-sources.jar")
        val fetched = newFetcher().fetch(artifact, destination)

        fetched.shouldNotBeNull()
        fetched.canonicalFile shouldBe destination.canonicalFile
        destination.isFile shouldBe true
        ZipFile(destination).use { zip ->
            zip.getEntry("Core.kt").shouldNotBeNull()
        }
    }

    @Test
    fun `returns null on 404`() {
        server.createContext(artifactPath) { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val destination = File(tempDir, "missing.jar")
        newFetcher().fetch(artifact, destination).shouldBeNull()
        destination.exists() shouldBe false
    }

    @Test
    fun `falls through 404 to next repository`() {
        val zipBytes = zipBytes("Core.kt", "class Core\n")
        server.createContext(artifactPath) { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val second = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        second.executor = Executors.newCachedThreadPool()
        second.createContext(artifactPath) { exchange ->
            exchange.sendResponseHeaders(200, zipBytes.size.toLong())
            exchange.responseBody.use { it.write(zipBytes) }
        }
        second.start()
        try {
            val destination = File(tempDir, "from-second.jar")
            val fetcher = MavenRepositorySourcesJarFetcher(
                repositories = listOf(
                    MavenRepositoryBase.parse(baseUrl),
                    MavenRepositoryBase.parse(
                        "http://127.0.0.1:${second.address.port}/repository/maven-public/",
                    ),
                ),
                client = testClient(),
            )
            fetcher.fetch(artifact, destination).shouldNotBeNull()
            destination.isFile shouldBe true
        } finally {
            second.stop(0)
        }
    }

    @Test
    fun `rejects non-zip body`() {
        val body = "not-a-jar".toByteArray()
        server.createContext(artifactPath) { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val error = shouldThrow<IOException> {
            newFetcher().fetch(artifact, File(tempDir, "bad.jar"))
        }
        error.message shouldContain "not a ZIP/JAR"
    }

    @Test
    fun `rejects oversized body`() {
        val body = ByteArray(64) { 1 }
        // PK header so size check fires before zip validation
        body[0] = 'P'.code.toByte()
        body[1] = 'K'.code.toByte()
        server.createContext(artifactPath) { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val error = shouldThrow<IOException> {
            newFetcher(maxBytes = 16L).fetch(artifact, File(tempDir, "big.jar"))
        }
        error.message shouldContain "exceeds 16 bytes"
    }

    @Test
    fun `sends basic auth from repository userinfo`() {
        var sawAuth: String? = null
        val zipBytes = zipBytes("Core.kt", "class Core\n")
        server.createContext(artifactPath) { exchange ->
            sawAuth = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, zipBytes.size.toLong())
            exchange.responseBody.use { it.write(zipBytes) }
        }

        val repo = MavenRepositoryBase.parse(
            "http://alice:s3cret@127.0.0.1:${server.address.port}/repository/maven-public/",
        )
        val destination = File(tempDir, "authed.jar")
        MavenRepositorySourcesJarFetcher(
            repositories = listOf(repo),
            client = testClient(),
        ).fetch(artifact, destination).shouldNotBeNull()

        val expected =
            "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        sawAuth shouldBe expected
    }

    @Test
    fun `http 500 is reported as repository failure`() {
        server.createContext(artifactPath) { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }

        val error = shouldThrow<IOException> {
            newFetcher().fetch(artifact, File(tempDir, "err.jar"))
        }
        error.message shouldContain "all sources repositories failed"
        error.message shouldContain "HTTP 500"
    }

    @Test
    fun `does not follow redirects to other hosts`() {
        server.createContext(artifactPath) { exchange ->
            exchange.responseHeaders.add("Location", "http://169.254.169.254/evil-sources.jar")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        // Use the production default client (Redirect.NEVER), not a test override.
        val error = shouldThrow<IOException> {
            MavenRepositorySourcesJarFetcher(
                repositories = listOf(MavenRepositoryBase.parse(baseUrl)),
            ).fetch(artifact, File(tempDir, "redir.jar"))
        }
        error.message shouldContain "HTTP 302"
        error.message.shouldNotContain("169.254.169.254/evil")
    }

    private fun newFetcher(maxBytes: Long = MavenRepositorySourcesJarFetcher.DEFAULT_MAX_BYTES) =
        MavenRepositorySourcesJarFetcher(
            repositories = listOf(MavenRepositoryBase.parse(baseUrl)),
            client = testClient(),
            maxBytes = maxBytes,
        )

    private fun testClient(): HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build()

    private fun zipBytes(entryName: String, body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(body.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
