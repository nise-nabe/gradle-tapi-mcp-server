package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import com.example.gradle.mcp.protocol.optionalNonNegativeInt
import com.example.gradle.mcp.protocol.optionalPositiveInt
import com.example.gradle.mcp.protocol.optionalString

data class ProjectTreeOptions(
    val maxDepth: Int? = null,
    val maxChildren: Int? = null,
    val projectPath: String? = null,
    val buildTreePath: String? = null,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>, parseBuildTreePath: Boolean = true): ProjectTreeOptions {
            val rawProjectPath = args.optionalString("projectPath")
            val projectPath =
                if (rawProjectPath.isNullOrBlank()) {
                    null
                } else {
                    ProjectTreeScope.normalizeProjectPath(rawProjectPath)
                }
            val buildTreePath = if (parseBuildTreePath) parseBuildTreePathArg(args) else null
            if (!projectPath.isNullOrBlank() && buildTreePath != null) {
                throw McpException(
                    McpErrorCode.INVALID_ARGUMENT,
                    "Specify either projectPath (connected build subtree) or " +
                        "buildTreePath (included builds / buildSrc), not both.",
                )
            }
            return ProjectTreeOptions(
                maxDepth = args.optionalNonNegativeInt("maxDepth"),
                maxChildren = args.optionalPositiveInt("maxChildren"),
                projectPath = projectPath,
                buildTreePath = buildTreePath,
            )
        }
    }
}

internal fun parseBuildTreePathArg(args: Map<String, Any>): String? {
    if (!args.containsKey("buildTreePath")) {
        return null
    }
    val raw = args["buildTreePath"]
    if (raw !is String) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "buildTreePath must be a string Tooling API identity path (e.g. :buildSrc).",
        )
    }
    if (raw.isBlank()) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "buildTreePath is required to target included builds or buildSrc. " +
                "Omit it to fetch the connected build's default project.",
        )
    }
    return ProjectTreeScope.normalizeBuildTreePath(raw)
}
