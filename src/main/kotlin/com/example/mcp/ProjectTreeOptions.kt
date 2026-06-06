package com.example.mcp

data class ProjectTreeOptions(
    val maxDepth: Int? = null,
    val maxChildren: Int? = null,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>): ProjectTreeOptions =
            ProjectTreeOptions(
                maxDepth = args.optionalPositiveInt("maxDepth"),
                maxChildren = args.optionalPositiveInt("maxChildren"),
            )
    }
}
