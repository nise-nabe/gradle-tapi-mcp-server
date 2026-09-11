package com.example.gradle.mcp.model.resolution

import com.example.gradle.mcp.resolution.McpConfigurationSummary
import com.example.gradle.mcp.resolution.McpDependencyResolution
import com.example.gradle.mcp.resolution.McpOutgoingArtifact
import com.example.gradle.mcp.resolution.McpOutgoingVariant
import com.example.gradle.mcp.resolution.McpResolvedComponent
import com.example.gradle.mcp.resolution.McpResolvedComponentIdentity
import com.example.gradle.mcp.resolution.McpResolvedDependencyEdge
import com.example.gradle.mcp.resolution.McpSelectionReason

internal object DependencyResolutionSerializers {
    fun toMap(model: McpDependencyResolution): Map<String, Any?> {
        val configuration = model.configuration?.trim().orEmpty()
        return if (configuration.isEmpty()) {
            catalogToMap(model)
        } else {
            graphToMap(model, configuration)
        }
    }

    private fun catalogToMap(model: McpDependencyResolution): Map<String, Any?> =
        buildMap {
            put("projectPath", model.projectPath)
            put("configurations", model.configurations.map { configurationToMap(it) })
            put("configurationCount", model.totalConfigurationCount)
            put("configurationsTruncated", model.isConfigurationsTruncated)
        }

    private fun graphToMap(model: McpDependencyResolution, configuration: String): Map<String, Any?> =
        buildMap {
            put("projectPath", model.projectPath)
            put("configuration", configuration)
            put("dependencyFilter", model.dependencyFilter)
            put("root", identityToMap(model.root))
            put("components", model.components.map { componentToMap(it) })
            put("dependencies", model.dependencies.map { edgeToMap(it) })
            put("componentsTruncated", model.isComponentsTruncated)
            put("dependenciesTruncated", model.isDependenciesTruncated)
            put("totalComponentCount", model.totalComponentCount)
            put("totalDependencyCount", model.totalDependencyCount)
        }

    private fun configurationToMap(summary: McpConfigurationSummary): Map<String, Any?> =
        buildMap {
            put("name", summary.name)
            put("canBeResolved", summary.isCanBeResolved)
            put("canBeConsumed", summary.isCanBeConsumed)
            val description = summary.description?.trim()?.takeIf { it.isNotEmpty() }
            if (description != null) {
                put("description", description)
            }
            if (summary.attributes.isNotEmpty()) {
                put("attributes", summary.attributes)
            }
            if (summary.outgoingVariants.isNotEmpty()) {
                put("outgoingVariants", summary.outgoingVariants.map { variantToMap(it) })
            }
        }

    private fun variantToMap(variant: McpOutgoingVariant): Map<String, Any?> =
        buildMap {
            put("name", variant.name)
            if (variant.attributes.isNotEmpty()) {
                put("attributes", variant.attributes)
            }
            if (variant.capabilities.isNotEmpty()) {
                put("capabilities", variant.capabilities)
            }
            if (variant.artifacts.isNotEmpty()) {
                put("artifacts", variant.artifacts.map { artifactToMap(it) })
            }
        }

    private fun artifactToMap(artifact: McpOutgoingArtifact): Map<String, Any?> =
        buildMap {
            put("name", artifact.name)
            if (!artifact.extension.isNullOrBlank()) {
                put("extension", artifact.extension)
            }
            if (!artifact.classifier.isNullOrBlank()) {
                put("classifier", artifact.classifier)
            }
            if (!artifact.type.isNullOrBlank()) {
                put("type", artifact.type)
            }
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
