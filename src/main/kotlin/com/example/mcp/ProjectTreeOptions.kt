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

private fun Map<String, Any>.optionalPositiveInt(key: String): Int? {
    val parsed = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
    return parsed?.takeIf { it > 0 }
}
