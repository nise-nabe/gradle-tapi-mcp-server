package com.example.gradle.mcp.connection

import com.example.gradle.mcp.support.noopProjectConnection
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GradleConnectionManagerAutoConnectTest {
    @Test
    fun `tryAutoConnectFromDirectory connects workspace project`() {
        val workspace = File(System.getProperty("user.dir")).canonicalFile
        val manager = GradleConnectionManager()
        try {
            manager.tryAutoConnectFromDirectory(workspace)

            manager.isConnected(workspace).shouldBeTrue()
            manager.status(workspace)["connected"] shouldBe true
        } finally {
            manager.disconnect(workspace)
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
