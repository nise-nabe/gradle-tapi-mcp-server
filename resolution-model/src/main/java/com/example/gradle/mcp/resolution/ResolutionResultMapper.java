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

        // Count every matching element for the truncation flags, but stop
        // materializing mapped objects once the response cap is reached.
        List<McpResolvedDependencyEdge> edges = new ArrayList<>();
        Set<String> includedComponentKeys = filter == null ? null : new LinkedHashSet<>();
        int totalDependencies = 0;
        for (DependencyResult dependency : resolutionResult.getAllDependencies()) {
            if (filter != null && !matchesFilter(dependency, filter)) {
                continue;
            }
            totalDependencies++;
            if (includedComponentKeys != null) {
                addComponentKeys(includedComponentKeys, dependency);
            }
            if (edges.size() < depLimit) {
                edges.add(toEdge(dependency));
            }
        }

        List<McpResolvedComponent> components = new ArrayList<>();
        int totalComponents = 0;
        for (ResolvedComponentResult component : resolutionResult.getAllComponents()) {
            if (includedComponentKeys != null
                    && !includedComponentKeys.contains(componentKey(component.getId()))) {
                continue;
            }
            totalComponents++;
            if (components.size() < componentLimit) {
                components.add(toComponent(component));
            }
        }

        ResolvedComponentResult root = resolutionResult.getRoot();
        return new DefaultMcpDependencyResolution(
                projectPath,
                configurationName,
                filter,
                toIdentity(root.getId()),
                components,
                edges,
                totalComponents > componentLimit,
                totalDependencies > depLimit,
                totalComponents,
                totalDependencies
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

    /**
     * Filter evaluation on the raw {@link DependencyResult}, equivalent to
     * {@link #matchesFilter(McpResolvedDependencyEdge, String)} but without
     * materializing the mapped edge. Used so edges that never enter the
     * response can be filtered and counted without object allocation.
     */
    private static boolean matchesFilter(DependencyResult dependency, String filter) {
        ComponentSelector requested;
        ResolvedComponentResult selected = null;
        ComponentIdentifier from;
        if (dependency instanceof ResolvedDependencyResult resolved) {
            requested = resolved.getRequested();
            selected = resolved.getSelected();
            from = resolved.getFrom().getId();
        } else {
            UnresolvedDependencyResult unresolved = (UnresolvedDependencyResult) dependency;
            requested = unresolved.getRequested();
            from = unresolved.getFrom().getId();
        }
        if (containsIgnoreCase(requestedDisplay(requested), filter)) {
            return true;
        }
        if (selected != null) {
            if (containsIgnoreCase(selected.getId().getDisplayName(), filter)) {
                return true;
            }
            if (containsIgnoreCase(moduleCoordinate(selected.getId()), filter)) {
                return true;
            }
        }
        return containsIgnoreCase(from.getDisplayName(), filter);
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

    private static void addComponentKeys(Set<String> keys, DependencyResult dependency) {
        if (dependency instanceof ResolvedDependencyResult resolved) {
            keys.add(componentKey(resolved.getFrom().getId()));
            keys.add(componentKey(resolved.getSelected().getId()));
        } else {
            keys.add(componentKey(((UnresolvedDependencyResult) dependency).getFrom().getId()));
        }
    }

    static String componentKey(McpResolvedComponentIdentity identity) {
        if (identity.getGroup() != null && identity.getModule() != null) {
            // Module component: group:module:version is a stable unique key.
            return "m:" + moduleCoordinate(identity);
        }
        if (identity.getModule() != null) {
            // Project components carry the project path in the module slot.
            return "p:" + identity.getModule();
        }
        return "d:" + identity.getDisplayName();
    }

    /**
     * {@link #componentKey(McpResolvedComponentIdentity)} on the raw
     * {@link ComponentIdentifier}; produces the same keys without allocating
     * the mapped identity.
     */
    static String componentKey(ComponentIdentifier id) {
        if (id instanceof ModuleComponentIdentifier module) {
            String version = module.getVersion() == null ? "" : module.getVersion();
            return "m:" + module.getGroup() + ":" + module.getModule() + ":" + version;
        }
        if (id instanceof ProjectComponentIdentifier project) {
            return "p:" + project.getProjectPath();
        }
        return "d:" + id.getDisplayName();
    }

    private static String moduleCoordinate(McpResolvedComponentIdentity identity) {
        if (identity.getGroup() == null || identity.getModule() == null) {
            return identity.getDisplayName();
        }
        String version = identity.getVersion() == null ? "" : identity.getVersion();
        return identity.getGroup() + ":" + identity.getModule() + ":" + version;
    }

    private static String moduleCoordinate(ComponentIdentifier id) {
        if (id instanceof ModuleComponentIdentifier module) {
            String version = module.getVersion() == null ? "" : module.getVersion();
            return module.getGroup() + ":" + module.getModule() + ":" + version;
        }
        return id.getDisplayName();
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
