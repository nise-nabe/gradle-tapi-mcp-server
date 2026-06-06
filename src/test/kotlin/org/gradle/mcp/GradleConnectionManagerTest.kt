package org.gradle.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class GradleConnectionManagerTest {
    private val manager = GradleConnectionManager()

    @Test
    fun `status reports disconnected initially`() {
        val status = manager.status()

        assertFalse(status.connected)
        assertNull(status.projectDirectory)
    }

    @Test
    fun `connect rejects missing project directory`() {
        val missingDirectory = File("build/tmp/nonexistent-project-dir").absolutePath

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.connect(ConnectionConfig(projectDirectory = missingDirectory))
        }

        assertEquals("Project directory does not exist: $missingDirectory", error.message)
    }

    @Test
    fun `withConnection requires an active connection`() {
        val error = assertThrows(IllegalStateException::class.java) {
            manager.withConnection { }
        }

        assertEquals(
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR.",
            error.message,
        )
    }

    @Test
    fun `disconnect without connection returns null`() {
        assertNull(manager.disconnect())
    }
}
