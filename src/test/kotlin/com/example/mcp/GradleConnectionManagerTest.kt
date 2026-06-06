package com.example.mcp

import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun `disconnect does not wait for long withConnection block`() {
        val connection = Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, _, _ -> null },
        ) as ProjectConnection
        manager.seedConnectionForTests(connection)

        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val disconnectCompleted = AtomicBoolean(false)

        val buildThread = Thread {
            manager.withConnection {
                blockEntered.countDown()
                releaseBlock.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true }
        buildThread.start()
        assertTrue(blockEntered.await(5, TimeUnit.SECONDS), "withConnection block should start")

        val disconnectThread = Thread {
            manager.disconnect()
            disconnectCompleted.set(true)
        }.apply { isDaemon = true }
        disconnectThread.start()
        disconnectThread.join(2_000)

        assertTrue(disconnectCompleted.get(), "disconnect must not block on withConnection work")
        releaseBlock.countDown()
        buildThread.join(2_000)
        assertFalse(buildThread.isAlive, "withConnection thread must terminate after releaseBlock")
    }

    @Test
    fun `withConnection rejects overlapping operations`() {
        val connection = Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, _, _ -> null },
        ) as ProjectConnection
        manager.seedConnectionForTests(connection)

        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstThread = Thread {
            manager.withConnection {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true }
        firstThread.start()
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val error = assertThrows(IllegalStateException::class.java) {
            manager.withConnectionResult { 42 }
        }
        assertTrue(error.message!!.contains("Another Gradle operation is in progress"))

        releaseFirst.countDown()
        firstThread.join(2_000)
    }

    @Test
    fun `disconnect resets operation permit for reconnect`() {
        val connection = Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, _, _ -> null },
        ) as ProjectConnection
        manager.seedConnectionForTests(connection)

        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val hungThread = Thread {
            manager.withConnection {
                blockEntered.countDown()
                releaseBlock.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true }
        hungThread.start()
        assertTrue(blockEntered.await(5, TimeUnit.SECONDS))

        manager.disconnect()
        manager.seedConnectionForTests(connection)

        var secondOperationRan = false
        manager.withConnection { secondOperationRan = true }
        assertTrue(secondOperationRan, "new operation must succeed after disconnect resets permit")

        releaseBlock.countDown()
        hungThread.join(2_000)
    }
}
