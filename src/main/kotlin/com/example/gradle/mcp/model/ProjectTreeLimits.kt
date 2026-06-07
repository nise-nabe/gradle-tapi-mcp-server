package com.example.gradle.mcp.model

internal data class DepthLimitResult(
    val omitChildren: Boolean,
    val truncated: Boolean,
    val totalChildCount: Int?,
)

internal data class ChildLimitResult(
    val visibleChildCount: Int,
    val truncated: Boolean,
    val totalChildCount: Int?,
)

internal object ProjectTreeLimits {
    fun applyDepthLimit(depth: Int, maxDepth: Int?, childCount: Int): DepthLimitResult {
        val omitChildren = maxDepth != null && depth >= maxDepth
        return DepthLimitResult(
            omitChildren = omitChildren,
            truncated = omitChildren && childCount > 0,
            totalChildCount = if (omitChildren && childCount > 0) childCount else null,
        )
    }

    fun applyChildLimit(totalChildren: Int, maxChildren: Int?): ChildLimitResult {
        if (maxChildren == null || totalChildren <= maxChildren) {
            return ChildLimitResult(
                visibleChildCount = totalChildren,
                truncated = false,
                totalChildCount = null,
            )
        }
        return ChildLimitResult(
            visibleChildCount = maxChildren,
            truncated = true,
            totalChildCount = totalChildren,
        )
    }
}
