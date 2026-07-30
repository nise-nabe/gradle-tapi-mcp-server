package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.optionalNonNegativeInt
import com.example.gradle.mcp.protocol.optionalPositiveInt
import com.example.gradle.mcp.protocol.optionalString

data class ProjectTreeOptions(
    val maxDepth: Int? = null,
    val maxChildren: Int? = null,
    val projectPath: String? = null,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>): ProjectTreeOptions {
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
            )
        }
    }
}
