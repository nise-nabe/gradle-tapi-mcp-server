package com.example.gradle.mcp.resolution;

import java.io.Serializable;
import java.util.List;

/**
 * Tooling API model for a configuration's {@code ResolutionResult} graph
 * or a configuration catalog (no task execution).
 *
 * <p>Catalog mode: {@link #getConfiguration()} is null/blank and {@link #getConfigurations()}
 * holds resolvable/consumable names. Graph mode: {@link #getConfiguration()} is set and
 * {@link #getConfigurations()} is empty.
 */
public interface McpDependencyResolution extends Serializable {
    String getProjectPath();

    String getConfiguration();

    String getDependencyFilter();

    McpResolvedComponentIdentity getRoot();

    List<McpResolvedComponent> getComponents();

    List<McpResolvedDependencyEdge> getDependencies();

    boolean isComponentsTruncated();

    boolean isDependenciesTruncated();

    int getTotalComponentCount();

    int getTotalDependencyCount();

    List<McpConfigurationSummary> getConfigurations();

    boolean isConfigurationsTruncated();

    int getTotalConfigurationCount();
}
