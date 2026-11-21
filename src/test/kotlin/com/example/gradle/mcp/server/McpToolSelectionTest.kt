package com.example.gradle.mcp.server

import com.example.gradle.mcp.DefaultGradleMcpRuntime
import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.connection.GradleConnectionManager
import com.example.gradle.mcp.createGradleTapiServer
import com.example.gradle.mcp.protocol.GradleTapiResourceTemplates
import com.example.gradle.mcp.protocol.McpToolProfiles
import com.example.gradle.mcp.protocol.ToolSelection
import com.example.gradle.mcp.protocol.ToolSelectionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test

class McpToolSelectionTest {
    @Test
    fun `default registration lists every tool and matches the all profile`() {
        val defaultTools = registeredToolNames(selection = null)
        val allProfile = registeredToolNames(ToolSelection(listOf("all")))
        val blankEnv = ServerCliOptions.parse(emptyArray(), env = emptyMap())

        blankEnv.toolEntries shouldBe null
        defaultTools shouldContainExactlyInAnyOrder allProfile
        defaultTools shouldContainAll McpToolProfiles.concreteToolNames()
        McpToolProfiles.profileNames.intersect(defaultTools).shouldBeEmpty()
    }

    @Test
    fun `all profile unions with explicit names and still rejects unknown names`() {
        val everyTool = registeredToolNames(selection = null)
        registeredToolNames(ToolSelection(listOf("all", "gradle_cancel_build"))) shouldContainExactlyInAnyOrder
            everyTool

        val error = shouldThrow<ToolSelectionException> {
            registeredToolNames(ToolSelection(listOf("all", "nope")))
        }
        error.message shouldContain "Unknown tool or profile name(s): nope"
        catalogListedIn(error) shouldContainExactlyInAnyOrder everyTool
    }

    @Test
    fun `minimal profile registers only its tools`() {
        val names = registeredToolNames(ToolSelection(listOf("minimal")))

        names shouldContainExactlyInAnyOrder McpToolProfiles.namedSets.getValue(McpToolProfiles.MINIMAL)
    }

    @Test
    fun `profile and tool names are unioned`() {
        val selection = ServerCliOptions.parse(
            arrayOf("--tools= minimal , gradle_connect , ,"),
            env = emptyMap(),
        ).toolEntries?.let(::ToolSelection) ?: error("expected a tool selection")

        registeredToolNames(selection) shouldContainExactlyInAnyOrder
            McpToolProfiles.namedSets.getValue(McpToolProfiles.MINIMAL) + "gradle_connect"
    }

    @Test
    fun `CLI tools flag wins over the environment variable`() {
        val selection = ServerCliOptions.parse(
            arrayOf("--tools=gradle_cancel_build"),
            env = mapOf(ServerCliOptions.TOOLS_ENV to "minimal,gradle_connect"),
        ).toolEntries?.let(::ToolSelection) ?: error("expected a tool selection")

        registeredToolNames(selection) shouldContainExactlyInAnyOrder listOf("gradle_cancel_build")
    }

    @Test
    fun `environment variable selects tools when the flag is absent`() {
        val selection = ServerCliOptions.parse(
            emptyArray(),
            env = mapOf(ServerCliOptions.TOOLS_ENV to " minimal "),
        ).toolEntries?.let(::ToolSelection) ?: error("expected a tool selection")

        registeredToolNames(selection) shouldContainExactlyInAnyOrder
            McpToolProfiles.namedSets.getValue(McpToolProfiles.MINIMAL)
    }

    @Test
    fun `unknown names fail with the registered catalog`() {
        val registered = registeredToolNames(selection = null)
        val error = shouldThrow<ToolSelectionException> {
            registeredToolNames(ToolSelection(listOf("nope", "minimal", "also-bad")))
        }

        error.message shouldContain "Unknown tool or profile name(s): nope, also-bad"
        error.message shouldContain "Valid profiles: all, minimal"
        catalogListedIn(error) shouldContainExactlyInAnyOrder registered
    }

    @Test
    fun `empty selection fails with the registered catalog`() {
        val registered = registeredToolNames(selection = null)
        val entries = ServerCliOptions.parse(arrayOf("--tools= , ,"), env = emptyMap()).toolEntries
            ?: error("expected an empty tool selection")
        val selection = ToolSelection(entries)
        val error = shouldThrow<ToolSelectionException> {
            registeredToolNames(selection)
        }

        selection.entries.shouldBeEmpty()
        error.message shouldContain "No MCP tools selected"
        error.message shouldContain "Valid profiles: all, minimal"
        catalogListedIn(error) shouldContainExactlyInAnyOrder registered
    }

    @Test
    fun `profile members that are not registered tools are rejected`() {
        val error = shouldThrow<ToolSelectionException> {
            ToolSelection(listOf(McpToolProfiles.MINIMAL)).resolve(setOf("gradle_run_tasks"))
        }

        error.message shouldContain "Profile references unknown tool(s):"
        error.message shouldContain "gradle_run_tests"
        error.message shouldContain "Valid tools: gradle_run_tasks"
    }

    @Test
    fun `resources stay registered when tools are limited`() {
        val server = newServer(ToolSelection(listOf("minimal")))
        server.tools.keys shouldContainExactlyInAnyOrder
            McpToolProfiles.namedSets.getValue(McpToolProfiles.MINIMAL)
        server.resourceTemplates.map { it.uriTemplate }.sorted() shouldBe
            GradleTapiResourceTemplates.all.map { it.uriTemplate }.sorted()
    }

    private fun catalogListedIn(error: ToolSelectionException): List<String> {
        val message = error.message ?: error("tool selection error has no message")
        return message.substringAfter("Valid tools: ")
            .lineSequence()
            .first()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun registeredToolNames(selection: ToolSelection?): Set<String> =
        newServer(selection).tools.keys.toSet()

    private fun newServer(selection: ToolSelection?): Server {
        val connectionManager = GradleConnectionManager()
        val runtime = DefaultGradleMcpRuntime(connectionManager, BuildExecutionManager(connectionManager))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            createGradleTapiServer(
                runtime = runtime,
                serverScope = scope,
                toolSelection = selection,
            )
        } finally {
            scope.cancel()
        }
    }
}
