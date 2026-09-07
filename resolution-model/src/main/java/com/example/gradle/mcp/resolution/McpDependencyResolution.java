package com.example.gradle.mcp.resolution;

import java.io.Serializable;
import java.util.List;

/**
 * Tooling API model for a configuration's {@code ResolutionResult} graph
 * (no task execution; dependency resolution only).
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
}
