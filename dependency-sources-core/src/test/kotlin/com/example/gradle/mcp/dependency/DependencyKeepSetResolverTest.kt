package com.example.gradle.mcp.dependency

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DependencyKeepSetResolverTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `artifacts keep-set uses gradleUserHome for local sources jar lookup`() {
        val gradleHome = File(tempDir, "ghome")
        val artifact = DependencyArtifactRef("org.demo", "widget", "0.1.0")
        val jar = placeSourcesJar(gradleHome, artifact)

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
    }

    @Test
    fun `artifacts keep-set reports missing when jar is only under unused gradle home`() {
        val usedHome = File(tempDir, "used")
        val unusedHome = File(tempDir, "unused")
        val artifact = DependencyArtifactRef("org.demo", "missing", "0.2.0")
        placeSourcesJar(unusedHome, artifact)

        val error = shouldThrow<IllegalArgumentException> {
            DependencyKeepSetResolver.resolve(
                connection = null,
                artifacts = listOf(artifact),
                sourcePaths = emptyList(),
                gradleUserHome = usedHome,
            )
        }
        error.message shouldContain artifact.gav()
    }

    private fun placeSourcesJar(gradleUserHome: File, artifact: DependencyArtifactRef): File {
        val moduleDir = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/${artifact.group}/${artifact.name}/${artifact.version}/deadbeef",
        )
        moduleDir.mkdirs()
        val jar = File(moduleDir, "${artifact.name}-${artifact.version}-sources.jar")
        jar.writeText("sources")
        return jar
    }
}
