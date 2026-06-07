package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GradleConnectionManagerTest {
    private val manager = GradleConnectionManager()

    @Test
    fun `status reports disconnected initially`() {
        val status = manager.status()

        status.connected.shouldBeFalse()
        status.projectDirectory.shouldBeNull()
        status.gradleVersion.shouldBeNull()
        status.javaHome.shouldBeNull()
        status.javaVersion.shouldBeNull()
        status.runtimeStackAvailable.shouldBeFalse()
    }

    @Test
    fun `status returns seeded runtime stack without calling getModel`() {
        val getModelCalls = AtomicInteger(0)
        val connection = connectionProxy(getModelCalls)
        val snapshot = BuildEnvironmentSnapshot(
            gradleVersion = "8.14",
            gradleUserHome = "/gradle/home",
            javaHome = "/jdk/home",
            javaVersion = "21.0.2",
            jvmArguments = listOf("-Xmx2g"),
        )
        manager.seedConnectionForTests(connection, environment = snapshot)

        val status = manager.status()

        status.connected.shouldBeTrue()
        status.gradleVersion shouldBe "8.14"
        status.javaHome shouldBe "/jdk/home"
        status.javaVersion shouldBe "21.0.2"
        status.runtimeStackAvailable.shouldBeTrue()
        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `status reports connected without runtime stack when cache is missing`() {
        val connection = connectionProxy(AtomicInteger(0))
        manager.seedConnectionForTests(connection)

        val status = manager.status()

        status.connected.shouldBeTrue()
        status.gradleVersion.shouldBeNull()
        status.javaHome.shouldBeNull()
        status.javaVersion.shouldBeNull()
        status.runtimeStackAvailable.shouldBeFalse()
    }

    @Test
    fun `disconnect clears runtime stack from status`() {
        val connection = connectionProxy(AtomicInteger(0))
        manager.seedConnectionForTests(
            connection,
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "8.14",
                gradleUserHome = null,
                javaHome = "/jdk/home",
                javaVersion = "21.0.2",
                jvmArguments = emptyList(),
            ),
        )

        manager.disconnect()

        val status = manager.status()
        status.connected.shouldBeFalse()
        status.runtimeStackAvailable.shouldBeFalse()
        status.gradleVersion.shouldBeNull()
    }

    @Test
    fun `connect rejects missing project directory`() {
        val missingDirectory = File("build/tmp/nonexistent-project-dir").absolutePath

        val error = shouldThrow<McpException> {
            manager.connect(ConnectionConfig(projectDirectory = missingDirectory))
        }

        error.code shouldBe McpErrorCode.PROJECT_NOT_FOUND
        error.message shouldBe "Project directory does not exist: $missingDirectory"
    }

    @Test
    fun `withConnection requires an active connection`() {
        val error = shouldThrow<McpException> {
            manager.withConnection { }
        }

        error.code shouldBe McpErrorCode.NOT_CONNECTED
        error.message shouldBe
            "Not connected to a Gradle project. Call gradle_connect first or set GRADLE_PROJECT_DIR."
    }

    @Test
    fun `disconnect without connection returns null`() {
        manager.disconnect().shouldBeNull()
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
        blockEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val disconnectThread = Thread {
            manager.disconnect()
            disconnectCompleted.set(true)
        }.apply { isDaemon = true }
        disconnectThread.start()
        disconnectThread.join(2_000)

        disconnectCompleted.get().shouldBeTrue()
        releaseBlock.countDown()
        buildThread.join(2_000)
        buildThread.isAlive.shouldBeFalse()
    }

    @Test
    fun `withConnection allows overlapping operations`() {
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
        firstEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        manager.withConnectionResult { 42 } shouldBe 42

        releaseFirst.countDown()
        firstThread.join(2_000)
    }

    @Test
    fun `disconnect allows reconnect after hung withConnection block`() {
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
        blockEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        manager.disconnect()
        manager.seedConnectionForTests(connection)

        var secondOperationRan = false
        manager.withConnection { secondOperationRan = true }
        secondOperationRan.shouldBeTrue()

        releaseBlock.countDown()
        hungThread.join(2_000)
    }

    private fun connectionProxy(getModelCalls: AtomicInteger): ProjectConnection =
        Proxy.newProxyInstance(
            ProjectConnection::class.java.classLoader,
            arrayOf(ProjectConnection::class.java),
            InvocationHandler { _, method: Method, _ ->
                if (method.name == "getModel") {
                    getModelCalls.incrementAndGet()
                }
                null
            },
        ) as ProjectConnection
}
