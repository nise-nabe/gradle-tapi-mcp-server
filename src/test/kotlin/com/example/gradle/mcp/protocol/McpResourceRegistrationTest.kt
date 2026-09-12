package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.registerBuildTools
import com.example.gradle.mcp.cache.registerCacheTools
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.connection.registerConnectionTools
import com.example.gradle.mcp.connection.registerJavaRuntimeTools
import com.example.gradle.mcp.dependency.registerDependencySourceTools
import com.example.gradle.mcp.model.registerModelTools
import com.example.gradle.mcp.model.resolution.registerDependencyResolutionTools
import com.example.gradle.mcp.support.seedNoopConnections
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class McpResourceRegistrationTest {
    @Test
    fun `registers five Phase 1 templates and concrete snapshots for connected projects`(
        @TempDir project: File,
    ) {
        registeredMcpToolNames.clear()
        registeredMcpResourceTemplateUris.clear()
        val connectionManager = GradleConnectionManager().also { it.seedNoopConnections(project) }
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = resourceEnabledServer()
        with(runtime) {
            server.registerConnectionTools(scope)
            server.registerJavaRuntimeTools(scope)
            server.registerCacheTools(scope)
            server.registerModelTools(scope)
            server.registerDependencyResolutionTools(scope)
            server.registerBuildTools(scope)
            server.registerDependencySourceTools(scope)
            server.registerGradleTapiResources()
        }

        val templateUris = GradleTapiResourceTemplates.all.map { it.uriTemplate }
        registeredMcpResourceTemplateUris.toList().sorted() shouldBe templateUris.sorted()
        server.resourceTemplates.map { it.uriTemplate }.sorted() shouldBe templateUris.sorted()
        server.resourceTemplates.map { it.mimeType }.distinct() shouldBe
            listOf(GradleTapiResourceUri.JSON_MIME_TYPE)
        server.resourceTemplates.shouldHaveSize(5)
        registeredMcpToolNames.toSet().shouldHaveSize(23)

        val listedUris = server.resources.keys.sorted()
        val expectedConcrete = GradleTapiResourceTemplates.concreteResources(project.canonicalFile)
            .map { it.uri }
            .sorted()
        listedUris shouldContainExactly expectedConcrete
        expectedConcrete.shouldHaveSize(4)
    }

    @Test
    fun `resources list is empty when no project is connected`() {
        registeredMcpResourceTemplateUris.clear()
        val connectionManager = GradleConnectionManager()
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val server = resourceEnabledServer()
        with(runtime) {
            server.registerGradleTapiResources()
        }

        server.resourceTemplates.shouldHaveSize(5)
        server.resources.keys.shouldBeEmpty()
    }

    private fun resourceEnabledServer(): Server =
        Server(
            serverInfo = Implementation(name = "test", version = "test"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(),
                    logging = ServerCapabilities.Logging,
                    resources = ServerCapabilities.Resources(
                        subscribe = false,
                        listChanged = false,
                    ),
                ),
                resourceTemplateMatcherFactory = QueryStrippingPathSegmentMatcher.factory,
            ),
        )
}
