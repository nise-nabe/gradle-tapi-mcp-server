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
            return ProjectTreeOptions(
                maxDepth = args.optionalNonNegativeInt("maxDepth"),
                maxChildren = args.optionalPositiveInt("maxChildren"),
                projectPath = projectPath,
                buildTreePath = if (parseBuildTreePath) parseBuildTreePathArg(args) else null,
            )
        }
    }
}

internal fun parseBuildTreePathArg(args: Map<String, Any>): String? {
    if (!args.containsKey("buildTreePath")) {
        return null
    }
    val raw = args["buildTreePath"]
    if (raw !is String || raw.isBlank()) {
        throw McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "buildTreePath is required to target included builds or buildSrc. " +
                "Omit it to fetch the connected build's default project.",
        )
    }
    return ProjectTreeScope.normalizeBuildTreePath(raw)
}
