package com.example.gradle.mcp.dependency

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalSourcesJarLocatorTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `finds sources jar under explicit gradleUserHome cache`() {
        val gradleHome = File(tempDir, "custom-gradle-home")
        val artifact = DependencyArtifactRef("com.example", "lib", "1.2.3")
        val jar = placeSourcesJar(gradleHome, artifact)

        val found = LocalSourcesJarLocator.find(artifact, gradleHome)

        found.shouldNotBeNull()
        found.canonicalFile shouldBe jar.canonicalFile
    }

    @Test
    fun `does not find sources jar that exists only under another gradleUserHome`() {
        val customHome = File(tempDir, "custom-home")
        val otherHome = File(tempDir, "other-home")
        val artifact = DependencyArtifactRef("com.example", "only-custom", "9.9.9")
        placeSourcesJar(customHome, artifact)

        LocalSourcesJarLocator.find(artifact, otherHome).shouldBeNull()
    }

    private fun placeSourcesJar(gradleUserHome: File, artifact: DependencyArtifactRef): File {
        val moduleDir = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/${artifact.group}/${artifact.name}/${artifact.version}/abc123",
        )
        moduleDir.mkdirs()
        val jar = File(moduleDir, "${artifact.name}-${artifact.version}-sources.jar")
        jar.writeText("sources")
        return jar
    }
}
