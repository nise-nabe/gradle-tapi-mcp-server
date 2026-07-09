package com.example.gradle.mcp.connection

import com.example.gradle.mcp.support.noopProjectConnection
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GradleConnectionManagerAutoConnectTest {
    @Test
    fun `tryAutoConnectFromDirectory connects minimal gradle project`(@TempDir projectDir: File) {
        writeMinimalGradleProject(projectDir)
        val manager = GradleConnectionManager()
        try {
            manager.tryAutoConnectFromDirectory(projectDir)

            manager.isConnected(projectDir).shouldBeTrue()
            manager.status(projectDir)["connected"] shouldBe true
        } finally {
            manager.disconnect(projectDir)
        }
    }

    @Test
    fun `tryAutoConnectFromDirectory is no-op when already connected`(@TempDir projectDir: File) {
        val manager = GradleConnectionManager()
        manager.seedConnectionForTests(noopProjectConnection(), projectDir)

        manager.tryAutoConnectFromDirectory(projectDir)

        manager.connectedProjectDirectories().single().canonicalFile shouldBe projectDir.canonicalFile
    }
}

private fun writeMinimalGradleProject(projectDir: File) {
    projectDir.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "auto-connect-test"
        """.trimIndent(),
    )
    projectDir.resolve("build.gradle.kts").writeText("")
}
