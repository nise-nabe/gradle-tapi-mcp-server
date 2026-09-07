package com.example.gradle.mcp.resolution;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DefaultMcpDependencyResolution implements McpDependencyResolution {
    private final String projectPath;
    private final String configuration;
    private final String dependencyFilter;
    private final McpResolvedComponentIdentity root;
    private final List<McpResolvedComponent> components;
    private final List<McpResolvedDependencyEdge> dependencies;
    private final boolean componentsTruncated;
    private final boolean dependenciesTruncated;
    private final int totalComponentCount;
    private final int totalDependencyCount;

    public DefaultMcpDependencyResolution(
            String projectPath,
            String configuration,
            String dependencyFilter,
            McpResolvedComponentIdentity root,
            List<McpResolvedComponent> components,
            List<McpResolvedDependencyEdge> dependencies,
            boolean componentsTruncated,
            boolean dependenciesTruncated,
            int totalComponentCount,
            int totalDependencyCount
    ) {
        this.projectPath = projectPath;
        this.configuration = configuration;
        this.dependencyFilter = dependencyFilter;
        this.root = root;
        this.components = immutable(components);
        this.dependencies = immutable(dependencies);
        this.componentsTruncated = componentsTruncated;
        this.dependenciesTruncated = dependenciesTruncated;
        this.totalComponentCount = totalComponentCount;
        this.totalDependencyCount = totalDependencyCount;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    public String getProjectPath() {
        return projectPath;
    }

    @Override
    public String getConfiguration() {
        return configuration;
    }

    @Override
    public String getDependencyFilter() {
        return dependencyFilter;
    }

    @Override
    public McpResolvedComponentIdentity getRoot() {
        return root;
    }

    @Override
    public List<McpResolvedComponent> getComponents() {
        return components;
    }

    @Override
    public List<McpResolvedDependencyEdge> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isComponentsTruncated() {
        return componentsTruncated;
    }

    @Override
    public boolean isDependenciesTruncated() {
        return dependenciesTruncated;
    }

    @Override
    public int getTotalComponentCount() {
        return totalComponentCount;
    }

    @Override
    public int getTotalDependencyCount() {
        return totalDependencyCount;
    }
}
