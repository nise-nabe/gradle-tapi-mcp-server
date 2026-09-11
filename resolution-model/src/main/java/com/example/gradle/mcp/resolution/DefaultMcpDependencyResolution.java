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
    private final List<McpConfigurationSummary> configurations;
    private final boolean configurationsTruncated;
    private final int totalConfigurationCount;

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
        this(
                projectPath,
                configuration,
                dependencyFilter,
                root,
                components,
                dependencies,
                componentsTruncated,
                dependenciesTruncated,
                totalComponentCount,
                totalDependencyCount,
                List.of(),
                false,
                0
        );
    }

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
            int totalDependencyCount,
            List<McpConfigurationSummary> configurations,
            boolean configurationsTruncated,
            int totalConfigurationCount
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
        this.configurations = immutable(configurations);
        this.configurationsTruncated = configurationsTruncated;
        this.totalConfigurationCount = totalConfigurationCount;
    }

    public static DefaultMcpDependencyResolution catalog(
            String projectPath,
            List<McpConfigurationSummary> configurations,
            boolean configurationsTruncated,
            int totalConfigurationCount
    ) {
        return new DefaultMcpDependencyResolution(
                projectPath,
                null,
                null,
                null,
                List.of(),
                List.of(),
                false,
                false,
                0,
                0,
                configurations,
                configurationsTruncated,
                totalConfigurationCount
        );
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

    @Override
    public List<McpConfigurationSummary> getConfigurations() {
        return configurations;
    }

    @Override
    public boolean isConfigurationsTruncated() {
        return configurationsTruncated;
    }

    @Override
    public int getTotalConfigurationCount() {
        return totalConfigurationCount;
    }
}
