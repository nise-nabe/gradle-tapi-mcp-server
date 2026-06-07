package com.example.gradle.mcp.model

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
