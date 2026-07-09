package com.example.gradle.mcp.connection

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.registerBuildTools
import com.example.gradle.mcp.cache.registerCacheTools
import com.example.gradle.mcp.model.registerModelTools
import com.example.gradle.mcp.protocol.allMcpToolSpecs
import com.example.gradle.mcp.protocol.registeredMcpToolNames
import com.example.gradle.mcp.support.runningTracker
import com.example.gradle.mcp.support.testBuildRecord
import com.example.gradle.mcp.support.testProjectDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import java.io.File

class ProjectLifecycleLockTest {
    @Test
    fun `lifecycle locks are scoped per canonical project path`() {
        val projectA = File("project-a").absoluteFile
        val projectB = File("project-b").absoluteFile

        ProjectLifecycleLock.forProject(projectA) shouldBe ProjectLifecycleLock.forProject(projectA)
        ProjectLifecycleLock.forProject(projectA) shouldNotBe ProjectLifecycleLock.forProject(projectB)
        ProjectLifecycleLock.global() shouldNotBe ProjectLifecycleLock.forProject(projectA)
    }

    @Test
    fun `connect rejects project with active build under lifecycle lock`() {
        val connectionManager = GradleConnectionManager()
        val buildManager = BuildExecutionManager(connectionManager)
        connectionManager.seedConnectionForTests(
            com.example.gradle.mcp.support.noopProjectConnection(),
            testProjectDirectory,
        )
        buildManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildManager)

        val error = shouldThrow<com.example.gradle.mcp.protocol.McpException> {
            connectProject(
                runtime = runtime,
                projectDirectory = testProjectDirectory,
                config = ConnectionConfig(projectDirectory = testProjectDirectory.path),
            )
        }
        error.code shouldBe com.example.gradle.mcp.protocol.McpErrorCode.BUILD_ALREADY_RUNNING
    }

    @Test
    fun `second background build for same project is rejected under lifecycle lock`() {
        val connectionManager = GradleConnectionManager()
        val buildManager = BuildExecutionManager(connectionManager)
        connectionManager.seedConnectionForTests(
            com.example.gradle.mcp.support.noopProjectConnection(),
            testProjectDirectory,
        )
        buildManager.seedRunningBuildForTests(
            testBuildRecord(
                id = "running-build",
                tracker = runningTracker(),
                projectDirectory = testProjectDirectory.absolutePath,
            ),
        )

        val error = shouldThrow<com.example.gradle.mcp.protocol.McpException> {
            buildManager.startBackground(
                request = com.example.gradle.mcp.build.BuildRunRequest(
                    projectDirectory = testProjectDirectory,
                    kind = com.example.gradle.mcp.build.BuildKind.TASKS,
                    tasks = listOf("build"),
                ),
                notifier = null,
            )
        }
        error.code shouldBe com.example.gradle.mcp.protocol.McpErrorCode.BUILD_ALREADY_RUNNING
    }
}

class McpToolRegistrationCatalogTest {
    @Test
    fun `catalog names match runtime registerTool names`() {
        registeredMcpToolNames.clear()
        val connectionManager = GradleConnectionManager()
        val buildExecutionManager = BuildExecutionManager(connectionManager)
        val runtime = DefaultGradleMcpRuntime(connectionManager, buildExecutionManager)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = Server(
            serverInfo = Implementation(name = "test", version = "test"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(),
                    logging = ServerCapabilities.Logging,
                ),
            ),
        )
        with(runtime) {
            server.registerConnectionTools(scope)
            server.registerJavaRuntimeTools(scope)
            server.registerCacheTools(scope)
            server.registerModelTools(scope)
            server.registerBuildTools(scope)
        }

        val catalogNames = allMcpToolSpecs().map { it.name }
        val catalogNameSet = catalogNames.toSet()
        val registeredNames = registeredMcpToolNames.toSet()
        val missingFromCatalog = registeredNames - catalogNameSet
        val missingFromRegistration = catalogNameSet - registeredNames

        missingFromCatalog.shouldBeEmpty()
        missingFromRegistration.shouldBeEmpty()
        catalogNames.size shouldBe registeredNames.size
        catalogNameSet.size shouldBe 17
    }
}
