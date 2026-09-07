package com.example.gradle.mcp.dependency

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Base64

/**
 * Fetches a missing `*-sources.jar` into [destination] (parent dirs created as needed).
 * Returns the jar file on success, or null when the remote artifact is not found.
 */
fun interface SourcesJarFetcher {
    fun fetch(artifact: DependencyArtifactRef, destination: File): File?
}

/**
 * Maven-layout repository base used when [downloadSources][IndexRequest.downloadSources] is true.
 * Examples: `https://repo1.maven.org/maven2/`, `https://nexus.example/repository/maven-public/`.
 * Optional `user:password@` in the URL is sent as HTTP Basic auth (useful for corporate mirrors).
 */
data class MavenRepositoryBase(
    val baseUri: URI,
) {
    init {
        validate(baseUri)
    }

    fun displayHost(): String = baseUri.host ?: "repository"

    fun sourcesJarUri(artifact: DependencyArtifactRef): URI {
        artifact.validate()
        val segments = ArrayList<String>().apply {
            addAll(artifact.group.split('.'))
            add(artifact.name)
            add(artifact.version)
            add("${artifact.name}-${artifact.version}-sources.jar")
        }
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "artifact coordinates must not produce empty or '.'/'..' path segments"
        }
        val basePath = (baseUri.rawPath ?: "/").let { if (it.endsWith("/")) it else "$it/" }
        // Build an absolute URI without URI.resolve(String), which treats leading "//" as authority.
        return URI(
            baseUri.scheme,
            baseUri.userInfo,
            baseUri.host,
            baseUri.port,
            basePath + segments.joinToString("/"),
            null,
            null,
        )
    }

    companion object {
        val MAVEN_CENTRAL: MavenRepositoryBase =
            MavenRepositoryBase(URI("https://repo1.maven.org/maven2/"))

        fun parse(raw: String): MavenRepositoryBase {
            require(raw.isNotBlank()) { "sourcesRepositories entry must not be blank" }
            val trimmed = raw.trim()
            val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            val uri =
                try {
                    URI(withSlash)
                } catch (error: Exception) {
                    throw IllegalArgumentException(
                        "invalid sourcesRepositories URL: ${redactUserInfo(withSlash)}",
                        error,
                    )
                }
            return MavenRepositoryBase(uri)
        }

        fun parseAll(rawUrls: List<String>): List<MavenRepositoryBase> {
            require(rawUrls.isNotEmpty()) { "sourcesRepositories must not be empty when provided" }
            return rawUrls.mapIndexed { index, raw ->
                try {
                    parse(raw)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "sourcesRepositories[$index]: ${error.message}",
                        error,
                    )
                }
            }
        }

        fun redactUserInfo(raw: String): String =
            try {
                val uri = URI(raw)
                if (uri.userInfo.isNullOrEmpty()) {
                    raw
                } else {
                    URI(uri.scheme, "***", uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
                }
            } catch (_: Exception) {
                raw.replace(Regex("://[^/]+@"), "://***@")
            }

        private fun validate(uri: URI) {
            val scheme = uri.scheme?.lowercase()
            require(scheme == "https" || scheme == "http") {
                "sourcesRepositories must use http or https: ${redactUserInfo(uri.toString())}"
            }
            require(!uri.host.isNullOrBlank()) {
                "sourcesRepositories must include a host: ${redactUserInfo(uri.toString())}"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "sourcesRepositories must not include query or fragment: ${redactUserInfo(uri.toString())}"
            }
        }
    }
}

/**
 * Downloads sources jars from one or more Maven-layout repositories (HTTPS/HTTP).
 * Defaults to Maven Central; pass corporate mirror bases via [repositories] for air-gapped /
 * intranet use (when set, only those bases are tried — Central is not appended).
 */
class MavenRepositorySourcesJarFetcher(
    private val repositories: List<MavenRepositoryBase>,
    private val client: HttpClient = defaultClient,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : SourcesJarFetcher {
    init {
        require(repositories.isNotEmpty()) { "repositories must not be empty" }
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    override fun fetch(artifact: DependencyArtifactRef, destination: File): File? {
        artifact.validate()
        val parent = destination.parentFile
            ?: throw IOException("destination has no parent: ${destination.path}")
        parent.mkdirs()

        val failures = ArrayList<String>()
        for (repository in repositories) {
            // Unique per attempt so concurrent downloads of the same GAV cannot share a .part file.
            val temp = Files.createTempFile(parent.toPath(), "${destination.name}.", ".part").toFile()
            try {
                when (val outcome = downloadOnce(repository, artifact, temp)) {
                    DownloadOutcome.NotFound -> {
                        temp.delete()
                    }
                    DownloadOutcome.Found -> {
                        moveIntoPlace(temp, destination)
                        return destination
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                temp.delete()
                throw IOException("interrupted while downloading sources for ${artifact.gav()}", error)
            } catch (error: IOException) {
                temp.delete()
                failures.add("${repository.displayHost()}: ${error.message}")
            } catch (error: Exception) {
                temp.delete()
                failures.add("${repository.displayHost()}: ${error.message}")
            }
        }
        if (failures.isNotEmpty()) {
            throw IOException(
                "all sources repositories failed for ${artifact.gav()}: " +
                    failures.joinToString("; "),
            )
        }
        return null
    }

    private fun downloadOnce(
        repository: MavenRepositoryBase,
        artifact: DependencyArtifactRef,
        temp: File,
    ): DownloadOutcome {
        val uri = repository.sourcesJarUri(artifact)
        val requestUri = uriWithoutUserInfo(uri)
        val requestBuilder =
            HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .header("Accept", "application/java-archive,application/octet-stream,*/*")
        basicAuthHeader(uri)?.let { requestBuilder.header("Authorization", it) }

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() == 404) {
            response.body().close()
            return DownloadOutcome.NotFound
        }
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw IOException("HTTP ${response.statusCode()} from ${repository.displayHost()}")
        }
        response.body().use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IOException("sources jar for ${artifact.gav()} exceeds $maxBytes bytes")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (temp.length() < 4L || !looksLikeZip(temp)) {
            throw IOException("downloaded sources jar for ${artifact.gav()} is not a ZIP/JAR")
        }
        return DownloadOutcome.Found
    }

    private fun moveIntoPlace(temp: File, destination: File) {
        try {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private enum class DownloadOutcome {
        Found,
        NotFound,
    }

    companion object {
        internal const val DEFAULT_MAX_BYTES: Long = 64L * 1024L * 1024L

        private val defaultClient: HttpClient =
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build()

        fun defaultOr(repositoryUrls: List<String>): SourcesJarFetcher =
            if (repositoryUrls.isEmpty()) {
                MavenCentralSourcesJarFetcher
            } else {
                MavenRepositorySourcesJarFetcher(MavenRepositoryBase.parseAll(repositoryUrls))
            }

        private fun uriWithoutUserInfo(uri: URI): URI =
            URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment)

        private fun basicAuthHeader(uri: URI): String? {
            val userInfo = uri.userInfo ?: return null
            val token =
                Base64.getEncoder().encodeToString(userInfo.toByteArray(StandardCharsets.UTF_8))
            return "Basic $token"
        }

        private fun looksLikeZip(file: File): Boolean {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) < 4) return false
                return header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            }
        }
    }
}

/** Default fetcher: Maven Central only. */
object MavenCentralSourcesJarFetcher :
    SourcesJarFetcher by MavenRepositorySourcesJarFetcher(listOf(MavenRepositoryBase.MAVEN_CENTRAL))

object SourcesJarCacheLayout {
    fun defaultDir(projectDirectory: File): File =
        File(projectDirectory, ".gradle/mcp-dependency-sources/jars")

    fun jarFile(cacheDir: File, artifact: DependencyArtifactRef): File {
        artifact.validate()
        val relative =
            artifact.group.replace('.', '/') + "/" +
                artifact.name + "/" +
                artifact.version + "/" +
                "${artifact.name}-${artifact.version}-sources.jar"
        val jar = File(cacheDir, relative)
        val cacheCanonical = cacheDir.canonicalFile
        val jarCanonical = jar.canonicalFile
        require(jarCanonical.path.startsWith(cacheCanonical.path + File.separator)) {
            "sources jar path escapes cache directory for ${artifact.gav()}"
        }
        return jar
    }
}
