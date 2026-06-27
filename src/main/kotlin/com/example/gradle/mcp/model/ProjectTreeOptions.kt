package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.optionalNonNegativeInt
import com.example.gradle.mcp.protocol.optionalPositiveInt

data class ProjectTreeOptions(
    val maxDepth: Int? = null,
    val maxChildren: Int? = null,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>): ProjectTreeOptions =
            ProjectTreeOptions(
                maxDepth = args.optionalNonNegativeInt("maxDepth"),
                maxChildren = args.optionalPositiveInt("maxChildren"),
            )
    }
}
