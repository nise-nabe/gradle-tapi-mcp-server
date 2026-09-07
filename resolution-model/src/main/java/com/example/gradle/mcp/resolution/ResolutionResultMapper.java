package com.example.gradle.mcp.resolution;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ComponentSelectionCause;
import org.gradle.api.artifacts.result.ComponentSelectionDescriptor;
import org.gradle.api.artifacts.result.ComponentSelectionReason;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps Gradle {@link ResolutionResult} into the serializable tooling model.
 * Package-visible for unit tests of filtering/truncation helpers.
 */
public final class ResolutionResultMapper {
    public static final int DEFAULT_MAX_DEPENDENCIES = 500;
    public static final int DEFAULT_MAX_COMPONENTS = 500;

    private ResolutionResultMapper() {
    }

    public static McpDependencyResolution map(
            String projectPath,
            String configurationName,
            String dependencyFilter,
            ResolutionResult resolutionResult,
            int maxDependencies,
            int maxComponents
    ) {
        int depLimit = maxDependencies > 0 ? maxDependencies : DEFAULT_MAX_DEPENDENCIES;
        int componentLimit = maxComponents > 0 ? maxComponents : DEFAULT_MAX_COMPONENTS;
        String filter = normalizeFilter(dependencyFilter);

        List<McpResolvedDependencyEdge> allEdges = new ArrayList<>();
        for (DependencyResult dependency : resolutionResult.getAllDependencies()) {
            McpResolvedDependencyEdge edge = toEdge(dependency);
            if (matchesFilter(edge, filter)) {
                allEdges.add(edge);
            }
        }

        List<McpResolvedComponent> allComponents = new ArrayList<>();
        Set<String> includedComponentKeys = new LinkedHashSet<>();
        if (filter == null) {
            for (ResolvedComponentResult component : resolutionResult.getAllComponents()) {
                allComponents.add(toComponent(component));
            }
        } else {
            for (McpResolvedDependencyEdge edge : allEdges) {
                addComponentKey(includedComponentKeys, edge.getFrom());
                addComponentKey(includedComponentKeys, edge.getSelected());
            }
            for (ResolvedComponentResult component : resolutionResult.getAllComponents()) {
                McpResolvedComponent mapped = toComponent(component);
                if (includedComponentKeys.contains(componentKey(mapped.getId()))) {
                    allComponents.add(mapped);
                }
            }
        }

        boolean dependenciesTruncated = allEdges.size() > depLimit;
        boolean componentsTruncated = allComponents.size() > componentLimit;
        List<McpResolvedDependencyEdge> edges =
                dependenciesTruncated ? new ArrayList<>(allEdges.subList(0, depLimit)) : allEdges;
        List<McpResolvedComponent> components =
                componentsTruncated ? new ArrayList<>(allComponents.subList(0, componentLimit)) : allComponents;

        ResolvedComponentResult root = resolutionResult.getRoot();
        return new DefaultMcpDependencyResolution(
                projectPath,
                configurationName,
                filter,
                toIdentity(root.getId()),
                components,
                edges,
                componentsTruncated,
                dependenciesTruncated,
                allComponents.size(),
                allEdges.size()
        );
    }

    static boolean matchesFilter(McpResolvedDependencyEdge edge, String filter) {
        if (filter == null) {
            return true;
        }
        if (containsIgnoreCase(edge.getRequested(), filter)) {
            return true;
        }
        if (edge.getSelected() != null && containsIgnoreCase(edge.getSelected().getDisplayName(), filter)) {
            return true;
        }
        if (edge.getSelected() != null && containsIgnoreCase(moduleCoordinate(edge.getSelected()), filter)) {
            return true;
        }
        return edge.getFrom() != null && containsIgnoreCase(edge.getFrom().getDisplayName(), filter);
    }

    static String normalizeFilter(String dependencyFilter) {
        if (dependencyFilter == null) {
            return null;
        }
        String trimmed = dependencyFilter.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static McpResolvedDependencyEdge toEdge(DependencyResult dependency) {
        if (dependency instanceof ResolvedDependencyResult) {
            ResolvedDependencyResult resolved = (ResolvedDependencyResult) dependency;
            ResolvedComponentResult selected = resolved.getSelected();
            return new DefaultMcpResolvedDependencyEdge(
                    toIdentity(resolved.getFrom().getId()),
                    requestedDisplay(resolved.getRequested()),
                    toIdentity(selected.getId()),
                    true,
                    null,
                    toReason(selected.getSelectionReason())
            );
        }
        UnresolvedDependencyResult unresolved = (UnresolvedDependencyResult) dependency;
        String failure = unresolved.getFailure() != null ? unresolved.getFailure().getMessage() : "unresolved";
        return new DefaultMcpResolvedDependencyEdge(
                toIdentity(unresolved.getFrom().getId()),
                requestedDisplay(unresolved.getRequested()),
                null,
                false,
                failure,
                null
        );
    }

    private static McpResolvedComponent toComponent(ResolvedComponentResult component) {
        String variantName = null;
        List<ResolvedVariantResult> variants = component.getVariants();
        if (variants != null && !variants.isEmpty()) {
            variantName = variants.get(0).getDisplayName();
        }
        return new DefaultMcpResolvedComponent(
                toIdentity(component.getId()),
                variantName,
                toReason(component.getSelectionReason())
        );
    }

    private static McpSelectionReason toReason(ComponentSelectionReason reason) {
        if (reason == null) {
            return null;
        }
        List<String> descriptions = new ArrayList<>();
        for (ComponentSelectionDescriptor descriptor : reason.getDescriptions()) {
            String description = descriptor.getDescription();
            if (description != null && !description.isBlank()) {
                descriptions.add(description);
            } else {
                ComponentSelectionCause cause = descriptor.getCause();
                if (cause != null) {
                    descriptions.add(cause.toString());
                }
            }
        }
        return new DefaultMcpSelectionReason(
                descriptions,
                reason.isConflictResolution(),
                reason.isConstrained(),
                reason.isExpected(),
                reason.isForced(),
                reason.isSelectedByRule(),
                reason.isCompositeSubstitution()
        );
    }

    private static McpResolvedComponentIdentity toIdentity(ComponentIdentifier id) {
        if (id instanceof ModuleComponentIdentifier) {
            ModuleComponentIdentifier module = (ModuleComponentIdentifier) id;
            return new DefaultMcpResolvedComponentIdentity(
                    module.getDisplayName(),
                    module.getGroup(),
                    module.getModule(),
                    module.getVersion()
            );
        }
        if (id instanceof ProjectComponentIdentifier) {
            ProjectComponentIdentifier project = (ProjectComponentIdentifier) id;
            return new DefaultMcpResolvedComponentIdentity(
                    project.getDisplayName(),
                    null,
                    project.getProjectPath(),
                    null
            );
        }
        return new DefaultMcpResolvedComponentIdentity(id.getDisplayName(), null, null, null);
    }

    private static String requestedDisplay(ComponentSelector selector) {
        if (selector instanceof ModuleComponentSelector) {
            ModuleComponentSelector module = (ModuleComponentSelector) selector;
            return module.getGroup() + ":" + module.getModule() + ":" + module.getVersion();
        }
        return selector.getDisplayName();
    }

    private static void addComponentKey(Set<String> keys, McpResolvedComponentIdentity identity) {
        if (identity != null) {
            keys.add(componentKey(identity));
        }
    }

    private static String componentKey(McpResolvedComponentIdentity identity) {
        return identity.getDisplayName();
    }

    private static String moduleCoordinate(McpResolvedComponentIdentity identity) {
        if (identity.getGroup() == null || identity.getModule() == null) {
            return identity.getDisplayName();
        }
        String version = identity.getVersion() == null ? "" : identity.getVersion();
        return identity.getGroup() + ":" + identity.getModule() + ":" + version;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
