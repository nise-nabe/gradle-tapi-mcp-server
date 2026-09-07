package com.example.gradle.mcp.dependency

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DependencyKeepSetResolverTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `artifacts keep-set uses gradleUserHome for local sources jar lookup`() {
        val gradleHome = File(tempDir, "ghome")
        val artifact = DependencyArtifactRef("org.demo", "widget", "0.1.0")
        val jar = placeGradleSourcesJar(gradleHome, artifact)

        val resolved = DependencyKeepSetResolver.resolve(
            connection = null,
            artifacts = listOf(artifact),
            sourcePaths = emptyList(),
            gradleUserHome = gradleHome,
        )

        resolved.mode shouldBe "explicit"
        resolved.members.shouldHaveSize(1)
        resolved.members.single().gav shouldBe artifact.gav()
        resolved.members.single().sourceRoot.canonicalFile shouldBe jar.canonicalFile
        resolved.downloadedGavs.shouldHaveSize(0)
    }

    @Test
    fun `artifacts keep-set reports missing when jar is only under unused gradle home`() {
        val usedHome = File(tempDir, "used")
        val unusedHome = File(tempDir, "unused")
        val artifact = DependencyArtifactRef("org.demo", "missing", "0.2.0")
        placeGradleSourcesJar(unusedHome, artifact)

        val error = shouldThrow<IllegalArgumentException> {
            DependencyKeepSetResolver.resolve(
                connection = null,
                artifacts = listOf(artifact),
                sourcePaths = emptyList(),
                gradleUserHome = usedHome,
            )
        }
        error.message shouldContain artifact.gav()
        error.message shouldContain "downloadSources=true"
        error.message shouldContain "Searched:"
        error.message shouldContain "Maven local"
    }

    @Test
    fun `downloadSources fetches missing jar into MCP cache`() {
        val artifact = DependencyArtifactRef("org.demo", "remote", "1.0.0")
        val cacheDir = File(tempDir, "mcp-jars")
        val payload = "package org.demo; class Remote {}"
        val fetcher = SourcesJarFetcher { ref, destination ->
            ref shouldBe artifact
            writeZipJar(destination, "Remote.java", payload)
            destination
        }

        val resolved = DependencyKeepSetResolver.resolve(
            connection = null,
            artifacts = listOf(artifact),
            sourcePaths = emptyList(),
            gradleUserHome = File(tempDir, "empty-ghome"),
            downloadSources = true,
            sourcesJarCacheDir = cacheDir,
            sourcesJarFetcher = fetcher,
        )

        resolved.members.shouldHaveSize(1)
        resolved.downloadedGavs.shouldContainExactly(artifact.gav())
        val cached = SourcesJarCacheLayout.jarFile(cacheDir, artifact)
        cached.isFile shouldBe true
        resolved.members.single().sourceRoot.canonicalFile shouldBe cached.canonicalFile
    }

    @Test
    fun `downloadSources failure includes repository guidance`() {
        val artifact = DependencyArtifactRef("org.demo", "gone", "9.9.9")
        val fetcher = SourcesJarFetcher { _, _ -> null }

        val error = shouldThrow<IllegalArgumentException> {
            DependencyKeepSetResolver.resolve(
                connection = null,
                artifacts = listOf(artifact),
                sourcePaths = emptyList(),
                gradleUserHome = File(tempDir, "empty"),
                downloadSources = true,
                sourcesJarCacheDir = File(tempDir, "jars"),
                sourcesJarFetcher = fetcher,
            )
        }
        error.message shouldContain artifact.gav()
        error.message shouldContain "Maven repository download did not succeed"
        error.message shouldContain "sourcesRepositories"
        error.message shouldContain "Searched:"
    }

    @Test
    fun `finds sources jar already present in MCP cache without download`() {
        val artifact = DependencyArtifactRef("org.demo", "cached", "2.0.0")
        val cacheDir = File(tempDir, "mcp-jars")
        val jar = SourcesJarCacheLayout.jarFile(cacheDir, artifact)
        writeZipJar(jar, "Cached.java", "class Cached {}")

        val found = LocalSourcesJarLocator.find(artifact, File(tempDir, "ghome"), cacheDir)
        found.shouldNotBeNull()
        found.canonicalFile shouldBe jar.canonicalFile
    }

    @Test
    fun `maven central uri uses coordinate path`() {
        val artifact = DependencyArtifactRef("com.example", "lib", "1.2.3")
        MavenRepositoryBase.MAVEN_CENTRAL.sourcesJarUri(artifact).toString() shouldBe
            "https://repo1.maven.org/maven2/com/example/lib/1.2.3/lib-1.2.3-sources.jar"
    }

    @Test
    fun `corporate repository base builds maven layout uri`() {
        val repo = MavenRepositoryBase.parse("https://nexus.example.com/repository/maven-public")
        val artifact = DependencyArtifactRef("com.acme", "core", "2.0.0")
        repo.sourcesJarUri(artifact).toString() shouldBe
            "https://nexus.example.com/repository/maven-public/com/acme/core/2.0.0/core-2.0.0-sources.jar"
    }

    @Test
    fun `rejects group with empty path segments`() {
        shouldThrow<IllegalArgumentException> {
            DependencyArtifactRef("com..example", "lib", "1.0.0").validate()
        }.message shouldContain "empty path segments"
    }

    @Test
    fun `rejects name that would escape cache directory`() {
        shouldThrow<IllegalArgumentException> {
            DependencyArtifactRef("com.example", "..", "1.0.0").validate()
        }.message shouldContain ".."
    }

    @Test
    fun `rejects coordinates with URI special characters`() {
        shouldThrow<IllegalArgumentException> {
            DependencyArtifactRef("com.example", "lib", "1.0.0@evil").validate()
        }.message shouldContain "illegal URI"
    }

    @Test
    fun `redactUserInfo hides credentials in messages`() {
        val redacted = MavenRepositoryBase.redactUserInfo(
            "https://alice:s3cret@nexus.example.com/repository/maven-public/",
        )
        redacted shouldContain "***"
        redacted.shouldNotContain("s3cret")
        redacted.shouldNotContain("alice:s3cret")
    }

    @Test
    fun `sourcesRepositories parse rejects non-http schemes`() {
        shouldThrow<IllegalArgumentException> {
            MavenRepositoryBase.parse("ftp://files.example/maven/")
        }.message shouldContain "http"
    }

    @Test
    fun `defaultOr uses only provided corporate repositories`() {
        val fetcher = MavenRepositorySourcesJarFetcher.defaultOr(
            listOf("https://nexus.example.com/repository/maven-public/"),
        )
        fetcher.shouldBeInstanceOf<MavenRepositorySourcesJarFetcher>()
    }

    @Test
    fun `locator returns null when caches empty`() {
        val artifact = DependencyArtifactRef("org.demo", "none", "0.0.1")
        LocalSourcesJarLocator.find(
            artifact,
            File(tempDir, "empty-home"),
            File(tempDir, "empty-mcp"),
        ).shouldBeNull()
    }

    private fun placeGradleSourcesJar(gradleUserHome: File, artifact: DependencyArtifactRef): File {
        val moduleDir = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/${artifact.group}/${artifact.name}/${artifact.version}/deadbeef",
        )
        moduleDir.mkdirs()
        val jar = File(moduleDir, "${artifact.name}-${artifact.version}-sources.jar")
        jar.writeText("sources")
        return jar
    }

    private fun writeZipJar(destination: File, entryName: String, body: String): File {
        destination.parentFile.mkdirs()
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(body.toByteArray())
            zip.closeEntry()
        }
        return destination
    }
}

class IdeaProjectPathScopeTest {
    @Test
    fun `normalizeOrNull treats blank and root as unscoped`() {
        IdeaProjectPathScope.normalizeOrNull(null).shouldBeNull()
        IdeaProjectPathScope.normalizeOrNull("").shouldBeNull()
        IdeaProjectPathScope.normalizeOrNull("  ").shouldBeNull()
        IdeaProjectPathScope.normalizeOrNull(":").shouldBeNull()
    }

    @Test
    fun `normalizeOrNull adds leading colon`() {
        IdeaProjectPathScope.normalizeOrNull("worker") shouldBe ":worker"
        IdeaProjectPathScope.normalizeOrNull(":app") shouldBe ":app"
    }

    @Test
    fun `normalizeOrNull rejects invalid paths`() {
        shouldThrow<IllegalArgumentException> {
            IdeaProjectPathScope.normalizeOrNull(":app:")
        }
        shouldThrow<IllegalArgumentException> {
            IdeaProjectPathScope.normalizeOrNull("::app")
        }
    }

    @Test
    fun `matchesSubtree includes descendants`() {
        IdeaProjectPathScope.matchesSubtree(":worker", ":worker") shouldBe true
        IdeaProjectPathScope.matchesSubtree(":worker:nested", ":worker") shouldBe true
        IdeaProjectPathScope.matchesSubtree(":workerx", ":worker") shouldBe false
        IdeaProjectPathScope.matchesSubtree(":other", ":worker") shouldBe false
    }

    @Test
    fun `projectPath with artifacts keep-set is rejected`() {
        val error = shouldThrow<IllegalArgumentException> {
            DependencyKeepSetResolver.resolve(
                connection = null,
                artifacts = listOf(DependencyArtifactRef("org.demo", "widget", "0.1.0")),
                sourcePaths = emptyList(),
                projectPath = ":worker",
            )
        }
        error.message shouldContain "projectPath applies only to the Idea keep-set"
    }
}
