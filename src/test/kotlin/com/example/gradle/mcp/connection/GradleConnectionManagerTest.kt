package com.example.gradle.mcp.connection

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.support.getModelCountingConnection
import com.example.gradle.mcp.support.seedCountingConnections
import com.example.gradle.mcp.support.seedNoopConnections
import com.example.gradle.mcp.support.statusBool
import com.example.gradle.mcp.support.statusStr
import com.example.gradle.mcp.support.withWorkspaceDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GradleConnectionManagerTest {
    private val manager = GradleConnectionManager()

    @Test
    fun `status reports disconnected initially`() {
        val status = manager.status()

        status.statusBool("connected").shouldBeFalse()
        status.statusStr("projectDirectory").shouldBeNull()
        status.statusStr("gradleVersion").shouldBeNull()
        status.statusStr("versionInfo").shouldBeNull()
        status.statusStr("javaHome").shouldBeNull()
        status.statusStr("javaVersion").shouldBeNull()
        status.statusBool("runtimeStackAvailable").shouldBeFalse()
    }

    @Test
    fun `status returns seeded runtime stack without calling getModel`() {
        val getModelCalls = AtomicInteger(0)
        val snapshot = BuildEnvironmentSnapshot(
            gradleVersion = "9.6",
            gradleUserHome = "/gradle/home",
            javaHome = "/jdk/home",
            javaVersion = "21.0.2",
            jvmArguments = listOf("-Xmx2g"),
            versionInfo = "Gradle 9.6\nBuild time: 2026-01-01",
        )
        manager.seedConnectionForTests(getModelCountingConnection(getModelCalls), environment = snapshot)

        val status = manager.status()

        status.statusBool("connected").shouldBeTrue()
        status.statusStr("gradleVersion") shouldBe "9.6"
        status.statusStr("versionInfo") shouldBe "Gradle 9.6\nBuild time: 2026-01-01"
        status.statusStr("javaHome") shouldBe "/jdk/home"
        status.statusStr("javaVersion") shouldBe "21.0.2"
        status.statusBool("runtimeStackAvailable").shouldBeTrue()
        getModelCalls.get() shouldBe 0
    }

    @Test
    fun `status omits versionInfo when snapshot has none`() {
        manager.seedConnectionForTests(
            getModelCountingConnection(),
            environment = BuildEnvironmentSnapshot(
                gradleVersion = "8.14",
                gradleUserHome = "/gradle/home",
                javaHome = "/jdk/home",
                javaVersion = "21.0.2",
                jvmArguments = emptyList(),
            ),
        )

        manager.status().statusStr("versionInfo").shouldBeNull()
    }

    @Test
    fun `status reports connected without runtime stack when cache is missing`() {
        val getModelCalls = AtomicInteger(0)
        manager.seedConnectionForTests(getModelCountingConnection(getModelCalls))

        val status = manager.status()

        status.statusBool("connected").shouldBeTrue()
        status.statusStr("gradleVersion").shouldBeNull()
        status.statusStr("versionInfo").shouldBeNull()
        status.statusStr("javaHome").shouldBeNull()
        status.statusStr("javaVersion").shouldBeNull()
        status.statusBool("runtimeStackAvailable").shouldBeFalse()
        getModelCalls.get() shouldBe 1
    }

    @Test
    fun `status lazily refreshes missing cached environment`(@TempDir javaHome: File) {
        File(javaHome, "release").writeText("JAVA_VERSION=\"21.0.2\"\n")
        val getModelCalls = AtomicInteger(0)
        val connection = com.example.gradle.mcp.connection.support.projectConnectionProxy(
            getModelCalls = getModelCalls,
            buildEnvironment = com.example.gradle.mcp.connection.support.buildEnvironmentProxy(
                com.example.gradle.mcp.connection.support.BuildEnvironmentProxyOptions(
                    javaHome = javaHome.path,
                    gradleVersion = "9.6.1",
                    versionInfo = "Gradle 9.6.1",
                ),
            ),
            launcher = com.example.gradle.mcp.connection.support.recordingBuildLauncher().launcher,
        )
        manager.seedConnectionForTests(connection, environment = null)

        val status = manager.status()

        status.statusBool("runtimeStackAvailable").shouldBeTrue()
        status.statusStr("gradleVersion") shouldBe "9.6.1"
        getModelCalls.get() shouldBe 1
        manager.cachedEnvironment(File("."))?.gradleVersion shouldBe "9.6.1"
    }

    @Test
    fun `cacheEnvironmentSnapshot updates status without reconnecting`() {
        manager.seedConnectionForTests(getModelCountingConnection(), environment = null)

        manager.cacheEnvironmentSnapshot(
            File("."),
            BuildEnvironmentSnapshot(
                gradleVersion = "9.6.1",
                gradleUserHome = null,
                javaHome = "/jdk/home",
                javaVersion = "21.0.2",
                jvmArguments = emptyList(),
            ),
        )

        val status = manager.status()
        status.statusBool("runtimeStackAvailable").shouldBeTrue()
        status.statusStr("gradleVersion") shouldBe "9.6.1"
    }

    @Test
    fun `disconnect clears runtime stack from status`() {
        manager.seedConnectionForTests(
            getModelCountingConnection(),
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
        status.statusBool("connected").shouldBeFalse()
        status.statusBool("runtimeStackAvailable").shouldBeFalse()
        status.statusStr("gradleVersion").shouldBeNull()
        status.statusStr("versionInfo").shouldBeNull()
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
    fun `ensureConnected keeps other project connections`(@TempDir projectA: File, @TempDir projectB: File) {
        manager.seedCountingConnections(projectA, projectB)

        manager.connectedProjectDirectories().shouldHaveSize(2)
        manager.isConnected(projectA).shouldBeTrue()
        manager.isConnected(projectB).shouldBeTrue()

        manager.disconnect(projectA)
        manager.isConnected(projectA).shouldBeFalse()
        manager.isConnected(projectB).shouldBeTrue()
    }

    @Test
    fun `ensureConnected rejects mismatched Gradle settings for an existing connection`(@TempDir project: File) {
        manager.seedConnectionForTests(
            getModelCountingConnection(),
            projectDirectory = project,
            config = ConnectionConfig(
                projectDirectory = project.path,
                gradleVersion = "8.14",
            ),
        )

        val error = shouldThrow<McpException> {
            manager.ensureConnected(
                ConnectionConfig(
                    projectDirectory = project.path,
                    gradleVersion = "9.0",
                ),
            )
        }

        error.code shouldBe McpErrorCode.INVALID_ARGUMENT
        error.message shouldBe
            "Project ${project.path} is already connected with different Gradle settings. " +
            "Call gradle_disconnect first or use matching gradleUserHome, gradleVersion, " +
            "and gradleInstallation."
    }

    @Test
    fun `ensureConnected accepts matching Gradle settings for an existing connection`(@TempDir project: File) {
        manager.seedConnectionForTests(
            getModelCountingConnection(),
            projectDirectory = project,
            config = ConnectionConfig(
                projectDirectory = project.path,
                gradleVersion = "8.14",
            ),
        )

        val info = manager.ensureConnected(
            ConnectionConfig(
                projectDirectory = project.path,
                gradleVersion = "8.14",
            ),
        )

        info.projectDirectory shouldBe project.path
        info.state shouldBe "connected"
    }

    @Test
    fun `ensureConnected accepts equivalent gradleUserHome paths`(@TempDir project: File, @TempDir gradleHome: File) {
        manager.seedConnectionForTests(
            getModelCountingConnection(),
            projectDirectory = project,
            config = ConnectionConfig(
                projectDirectory = project.path,
                gradleUserHome = gradleHome.absolutePath,
            ),
        )

        val info = manager.ensureConnected(
            ConnectionConfig(
                projectDirectory = project.path,
                gradleUserHome = File(gradleHome.path).path,
            ),
        )

        info.state shouldBe "connected"
    }

    @Test
    fun `ensureConnected accepts equivalent gradleInstallation paths`(
        @TempDir project: File,
        @TempDir gradleInstallation: File,
    ) {
        manager.seedConnectionForTests(
            getModelCountingConnection(),
            projectDirectory = project,
            config = ConnectionConfig(
                projectDirectory = project.path,
                gradleInstallation = gradleInstallation.absolutePath,
            ),
        )

        val info = manager.ensureConnected(
            ConnectionConfig(
                projectDirectory = project.path,
                gradleInstallation = File(gradleInstallation.path).path,
            ),
        )

        info.state shouldBe "connected"
    }

    @Test
    fun `disconnect finds connection after project directory is removed`(@TempDir project: File) {
        manager.seedCountingConnections(project)
        manager.isConnected(project).shouldBeTrue()

        project.deleteRecursively()
        val removedPath = project.absolutePath

        val disconnected = manager.disconnect(ProjectDirectoryResolver.bestEffortDirectory(removedPath))

        disconnected?.state shouldBe "disconnected"
        manager.isConnected(ProjectDirectoryResolver.bestEffortDirectory(removedPath)).shouldBeFalse()
    }

    @Test
    fun `status finds connection after project directory is removed`(@TempDir project: File) {
        val canonicalPath = project.canonicalFile.path
        manager.seedCountingConnections(project)
        project.deleteRecursively()

        manager.status(ProjectDirectoryResolver.bestEffortDirectory(canonicalPath)).statusBool("connected").shouldBeTrue()
    }

    @Test
    fun `status omits misleading legacy flat fields when default project is ambiguous`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        manager.seedCountingConnections(projectA, projectB)

        val status = manager.status()

        status.statusBool("connected").shouldBeFalse()
        status.statusBool("connectedAny").shouldBeTrue()
        status.statusStr("defaultProjectDirectory").shouldBeNull()
        status.statusStr("gradleVersion").shouldBeNull()
        (status["connections"] as List<*>).shouldHaveSize(2)
    }

    @Test
    fun `defaultProjectDirectory is null when multiple projects are connected without workspace env`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        manager.seedCountingConnections(projectA, projectB)
        manager.defaultProjectDirectory().shouldBeNull()
    }

    @Test
    fun `defaultProjectDirectory is null when workspace env is set but not connected`(
        @TempDir workspace: File,
        @TempDir connected: File,
    ) {
        withWorkspaceDirectory(workspace) {
            manager.seedCountingConnections(connected)
            manager.defaultProjectDirectory().shouldBeNull()
        }
    }

    @Test
    fun `status reports effective defaultProjectDirectory when workspace env is set but not connected`(
        @TempDir workspace: File,
        @TempDir connected: File,
    ) {
        withWorkspaceDirectory(workspace) {
            manager.seedCountingConnections(connected)

            val status = manager.status()

            status.statusStr("defaultProjectDirectory") shouldBe connected.canonicalFile.path
            status.statusStr("projectDirectory") shouldBe connected.canonicalFile.path
            status.statusBool("connected").shouldBeTrue()
        }
    }

    @Test
    fun `defaultProjectDirectory returns sole connection when only one project is connected`(@TempDir project: File) {
        manager.seedCountingConnections(project)
        manager.defaultProjectDirectory() shouldBe project.canonicalFile
    }

    @Test
    fun `status lists all connections when projectDirectory is omitted`(@TempDir projectA: File, @TempDir projectB: File) {
        manager.seedCountingConnections(projectA, projectB)
        (manager.status()["connections"] as List<*>).shouldHaveSize(2)
    }

    @Test
    fun `disconnect does not wait for long withConnection block`() {
        manager.seedNoopConnections(File("."))

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
        manager.seedNoopConnections(File("."))

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
        val connection = getModelCountingConnection()
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

    @Test
    fun `withConnection on different projects does not block each other`(
        @TempDir projectA: File,
        @TempDir projectB: File,
    ) {
        manager.seedCountingConnections(projectA, projectB)

        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val buildThread = Thread {
            manager.withConnection(projectA) {
                blockEntered.countDown()
                releaseBlock.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true }
        buildThread.start()
        blockEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

        manager.withConnectionResult(projectB) { 42 } shouldBe 42

        releaseBlock.countDown()
        buildThread.join(2_000)
    }
}
