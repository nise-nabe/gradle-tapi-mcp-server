package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.resolution.McpDependencyResolution
import com.example.gradle.mcp.resolution.McpResolvedComponent
import com.example.gradle.mcp.resolution.McpResolvedComponentIdentity
import com.example.gradle.mcp.resolution.McpResolvedDependencyEdge
import com.example.gradle.mcp.resolution.McpSelectionReason

internal object DependencyResolutionSerializers {
    fun toMap(model: McpDependencyResolution): Map<String, Any?> =
        buildMap {
            put("projectPath", model.projectPath)
            put("configuration", model.configuration)
            put("dependencyFilter", model.dependencyFilter)
            put("root", identityToMap(model.root))
            put("components", model.components.map { componentToMap(it) })
            put("dependencies", model.dependencies.map { edgeToMap(it) })
            put("componentsTruncated", model.isComponentsTruncated)
            put("dependenciesTruncated", model.isDependenciesTruncated)
            put("totalComponentCount", model.totalComponentCount)
            put("totalDependencyCount", model.totalDependencyCount)
        }

    private fun componentToMap(component: McpResolvedComponent): Map<String, Any?> =
        buildMap {
            put("id", identityToMap(component.id))
            put("variantName", component.variantName)
            put("selectionReason", reasonToMap(component.selectionReason))
        }

    private fun edgeToMap(edge: McpResolvedDependencyEdge): Map<String, Any?> =
        buildMap {
            put("from", identityToMap(edge.from))
            put("requested", edge.requested)
            put("selected", identityToMap(edge.selected))
            put("resolved", edge.isResolved)
            put("failureMessage", edge.failureMessage)
            put("selectionReason", reasonToMap(edge.selectionReason))
        }

    private fun identityToMap(identity: McpResolvedComponentIdentity?): Map<String, Any?>? {
        if (identity == null) {
            return null
        }
        return buildMap {
            put("displayName", identity.displayName)
            put("group", identity.group)
            put("module", identity.module)
            put("version", identity.version)
        }
    }

    private fun reasonToMap(reason: McpSelectionReason?): Map<String, Any?>? {
        if (reason == null) {
            return null
        }
        return buildMap {
            put("descriptions", reason.descriptions)
            put("conflictResolution", reason.isConflictResolution)
            put("constrained", reason.isConstrained)
            put("expected", reason.isExpected)
            put("forced", reason.isForced)
            put("selectedByRule", reason.isSelectedByRule)
            put("compositeSubstitution", reason.isCompositeSubstitution)
        }
    }
}
