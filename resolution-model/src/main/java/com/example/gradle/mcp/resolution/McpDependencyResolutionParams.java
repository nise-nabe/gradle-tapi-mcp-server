package com.example.gradle.mcp.resolution;

/**
 * Parameters for {@link McpDependencyResolution} tooling model requests.
 * Gradle creates a proxy; unset ints are 0 (treated as defaults by the builder).
 */
public interface McpDependencyResolutionParams {
    String getProjectPath();

    void setProjectPath(String projectPath);

    String getConfiguration();

    void setConfiguration(String configuration);

    /** Optional substring filter (requested or selected display name), like dependencyInsight. */
    String getDependency();

    void setDependency(String dependency);

    /** Max dependency edges to return; 0 = builder default. */
    int getMaxDependencies();

    void setMaxDependencies(int maxDependencies);

    /** Max components to return; 0 = builder default. */
    int getMaxComponents();

    void setMaxComponents(int maxComponents);
}
