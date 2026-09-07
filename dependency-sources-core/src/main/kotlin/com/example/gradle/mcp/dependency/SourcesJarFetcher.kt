package com.example.gradle.mcp.dependency

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Fetches a missing `*-sources.jar` into [destination] (parent dirs created as needed).
 * Returns the jar file on success, or null when the remote artifact is not found.
 */
fun interface SourcesJarFetcher {
    fun fetch(artifact: DependencyArtifactRef, destination: File): File?
}

/**
 * Downloads sources jars from Maven Central only (HTTPS). Private/custom repos are
 * out of scope — use `sourcePaths` or pre-populate caches for those.
 */
object MavenCentralSourcesJarFetcher : SourcesJarFetcher {
    private const val HOST: String = "repo1.maven.org"
    private const val MAX_BYTES: Long = 64L * 1024L * 1024L

    private val client: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

    override fun fetch(artifact: DependencyArtifactRef, destination: File): File? {
        artifact.validate()
        val uri = mavenCentralUri(artifact)
        require(uri.host == HOST) { "unexpected Maven Central host: ${uri.host}" }

        val parent = destination.parentFile
            ?: throw IOException("destination has no parent: ${destination.path}")
        parent.mkdirs()
        // Unique per attempt so concurrent downloads of the same GAV cannot share a .part file.
        val temp = Files.createTempFile(parent.toPath(), "${destination.name}.", ".part").toFile()
        try {
            val request =
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .header("Accept", "application/java-archive,application/octet-stream,*/*")
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() == 404) {
                response.body().close()
                return null
            }
            if (response.statusCode() !in 200..299) {
                response.body().close()
                throw IOException(
                    "Maven Central returned HTTP ${response.statusCode()} for ${artifact.gav()}",
                )
            }
            response.body().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            throw IOException(
                                "sources jar for ${artifact.gav()} exceeds $MAX_BYTES bytes",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (temp.length() < 4L || !looksLikeZip(temp)) {
                throw IOException("downloaded sources jar for ${artifact.gav()} is not a ZIP/JAR")
            }
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            return destination
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            temp.delete()
            throw IOException("interrupted while downloading sources for ${artifact.gav()}", error)
        } catch (error: IOException) {
            temp.delete()
            throw error
        } catch (error: Exception) {
            temp.delete()
            throw IOException("failed to download sources for ${artifact.gav()}: ${error.message}", error)
        }
    }

    internal fun mavenCentralUri(artifact: DependencyArtifactRef): URI {
        val path =
            "/maven2/" +
                artifact.group.replace('.', '/') + "/" +
                artifact.name + "/" +
                artifact.version + "/" +
                "${artifact.name}-${artifact.version}-sources.jar"
        return URI("https", HOST, path, null)
    }

    private fun looksLikeZip(file: File): Boolean {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) < 4) return false
            // PK\u0003\u0004 local file header, or PK\u0005\u0006 empty zip
            return header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    }
}

object SourcesJarCacheLayout {
    fun defaultDir(projectDirectory: File): File =
        File(projectDirectory, ".gradle/mcp-dependency-sources/jars")

    fun jarFile(cacheDir: File, artifact: DependencyArtifactRef): File {
        artifact.validate()
        return File(
            cacheDir,
            artifact.group.replace('.', '/') + "/" +
                artifact.name + "/" +
                artifact.version + "/" +
                "${artifact.name}-${artifact.version}-sources.jar",
        )
    }
}
