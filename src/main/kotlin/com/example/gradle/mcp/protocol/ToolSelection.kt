package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Named subsets of the tools [Server.registerTool] publishes.
 *
 * [ALL] means every name collected from registration. Other profiles expand to
 * a fixed list. Add a profile by putting one entry in [namedSets]; registration
 * checks those names against the tools that are actually registered.
 */
internal object McpToolProfiles {
    const val ALL: String = "all"
    const val MINIMAL: String = "minimal"

    val namedSets: Map<String, Set<String>> = mapOf(
        MINIMAL to linkedSetOf(
            "gradle_run_tasks",
            "gradle_run_tests",
            "gradle_get_build_status",
            "gradle_get_project_overview",
            "gradle_cancel_build",
        ),
    )

    val profileNames: Set<String> = setOf(ALL) + namedSets.keys

    /** Tool names named by a fixed profile. [ALL] is not included. */
    fun concreteToolNames(): Set<String> = namedSets.values.flatten().toSet()
}

/**
 * Comma-separated `--tools` / `GRADLE_TAPI_TOOLS` entries after trimming.
 * An empty [entries] list is a present-but-empty selection and fails at [resolve].
 */
internal data class ToolSelection(val entries: List<String>) {
    /**
     * Unions profile expansions and exact tool names against [knownTools],
     * the names registration offered. [McpToolProfiles.ALL] selects every
     * known tool. Unknown tokens and an empty result throw [ToolSelectionException].
     */
    fun resolve(knownTools: Set<String>): Set<String> {
        if (entries.isEmpty()) {
            throw toolSelectionException(EMPTY_SELECTION, knownTools)
        }
        val unknownTokens = linkedSetOf<String>()
        val missingProfileTools = linkedSetOf<String>()
        val selected = linkedSetOf<String>()
        var includeAll = false
        for (entry in entries) {
            val profileTools = McpToolProfiles.namedSets[entry]
            when {
                entry == McpToolProfiles.ALL -> includeAll = true
                profileTools != null -> {
                    missingProfileTools += profileTools.filter { it !in knownTools }
                    selected += profileTools
                }
                entry in knownTools -> selected += entry
                else -> unknownTokens += entry
            }
        }
        if (unknownTokens.isNotEmpty() || missingProfileTools.isNotEmpty()) {
            throw toolSelectionException(unknownMessage(unknownTokens, missingProfileTools), knownTools)
        }
        if (includeAll) {
            return knownTools
        }
        if (selected.isEmpty()) {
            throw toolSelectionException(EMPTY_SELECTION, knownTools)
        }
        return selected
    }

    companion object {
        private const val EMPTY_SELECTION =
            "No MCP tools selected. Pass at least one tool name or profile."

        private fun unknownMessage(
            unknownTokens: Set<String>,
            missingProfileTools: Set<String>,
        ): String {
            val parts = mutableListOf<String>()
            if (unknownTokens.isNotEmpty()) {
                parts += "Unknown tool or profile name(s): ${unknownTokens.joinToString(", ")}"
            }
            if (missingProfileTools.isNotEmpty()) {
                parts += "Profile references unknown tool(s): ${missingProfileTools.sorted().joinToString(", ")}"
            }
            return parts.joinToString("\n")
        }

        private fun toolSelectionException(problem: String, knownTools: Set<String>): ToolSelectionException =
            ToolSelectionException(
                buildString {
                    append(problem)
                    append('\n')
                    append("Valid profiles: ")
                    append(McpToolProfiles.profileNames.sorted().joinToString(", "))
                    append('\n')
                    append("Valid tools: ")
                    append(knownTools.sorted().joinToString(", "))
                },
            )
    }
}

internal class ToolSelectionException(message: String) : IllegalArgumentException(message)

/**
 * Limits one registration pass to a resolved [ToolSelection].
 *
 * The first pass records every name passed to [Server.registerTool] and does
 * not publish tools. [ToolSelection.resolve] checks profiles against that set,
 * then the second pass publishes only the allowed names.
 */
internal object ToolRegistration {
    private sealed class Mode {
        class Record(val names: MutableSet<String>) : Mode()
        class Allow(val names: Set<String>) : Mode()
    }

    private val mode = ThreadLocal<Mode?>()

    fun <T> record(names: MutableSet<String>, block: () -> T): T = withMode(Mode.Record(names), block)

    fun <T> allow(names: Set<String>, block: () -> T): T = withMode(Mode.Allow(names), block)

    /** `true` when [name] should be published. Outside a selection pass, every tool is published. */
    fun accept(name: String): Boolean = when (val current = mode.get()) {
        null -> true
        is Mode.Record -> {
            current.names.add(name)
            false
        }
        is Mode.Allow -> name in current.names
    }

    private inline fun <T> withMode(next: Mode, block: () -> T): T {
        val previous = mode.get()
        mode.set(next)
        try {
            return block()
        } finally {
            if (previous == null) {
                mode.remove()
            } else {
                mode.set(previous)
            }
        }
    }
}

internal fun Server.registerSelectedTools(selection: ToolSelection?, register: Server.() -> Unit) {
    if (selection == null) {
        register()
        return
    }
    val offered = linkedSetOf<String>()
    ToolRegistration.record(offered) { register() }
    val allowed = selection.resolve(offered)
    ToolRegistration.allow(allowed) { register() }
}
