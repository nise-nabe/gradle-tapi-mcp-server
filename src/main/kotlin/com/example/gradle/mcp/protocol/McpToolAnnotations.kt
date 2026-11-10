package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

/**
 * Shared [ToolAnnotations] presets for MCP tool registration. Hints follow the
 * MCP spec: `destructiveHint` only matters when `readOnlyHint` is false, and
 * `openWorldHint` marks tools that may reach external entities (Gradle/Maven
 * repositories, distribution downloads, arbitrary build logic).
 */
internal object McpToolAnnotations {
    /** Local read-only query; repeatable, no external interaction. */
    val READ_ONLY = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    )

    /** Read-only query that runs Gradle/Tooling API work and may download artifacts. */
    val READ_ONLY_OPEN_WORLD = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = true,
    )

    /** Mutates server state locally; additive and safe to repeat. */
    val WRITE = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    )

    /** Mutates server state and may download distributions/artifacts. */
    val WRITE_OPEN_WORLD = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = true,
    )

    /** Runs arbitrary build logic; may modify the environment and reach the network. */
    val EXECUTE = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = true,
    )
}
