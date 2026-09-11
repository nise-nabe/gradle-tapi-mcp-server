package com.example.gradle.mcp.resolution;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;

import java.io.Serializable;

/**
 * Fetches {@link McpDependencyResolution} with parameters. The model builder is registered
 * by {@link McpDependencyResolutionPlugin} via an MCP-owned init script.
 *
 * <p>When {@code configuration} is null or blank, the action returns a configuration catalog
 * instead of a {@code ResolutionResult} graph.
 */
public final class FetchDependencyResolutionAction
        implements BuildAction<McpDependencyResolution>, Serializable {
    private static final long serialVersionUID = 2L;

    private final String projectPath;
    private final String configuration;
    private final String dependency;
    private final int maxDependencies;
    private final int maxComponents;
    private final boolean includeAttributes;
    private final boolean includeOutgoingVariants;
    private final int maxConfigurations;

    public FetchDependencyResolutionAction(
            String projectPath,
            String configuration,
            String dependency,
            int maxDependencies,
            int maxComponents
    ) {
        this(projectPath, configuration, dependency, maxDependencies, maxComponents, false, false, 0);
    }

    public FetchDependencyResolutionAction(
            String projectPath,
            String configuration,
            String dependency,
            int maxDependencies,
            int maxComponents,
            boolean includeAttributes,
            boolean includeOutgoingVariants,
            int maxConfigurations
    ) {
        this.projectPath = projectPath;
        this.configuration = configuration;
        this.dependency = dependency;
        this.maxDependencies = maxDependencies;
        this.maxComponents = maxComponents;
        this.includeAttributes = includeAttributes;
        this.includeOutgoingVariants = includeOutgoingVariants;
        this.maxConfigurations = maxConfigurations;
    }

    @Override
    public McpDependencyResolution execute(BuildController controller) {
        return controller.getModel(
                McpDependencyResolution.class,
                McpDependencyResolutionParams.class,
                params -> {
                    params.setProjectPath(projectPath);
                    params.setConfiguration(configuration);
                    params.setDependency(dependency);
                    params.setMaxDependencies(maxDependencies);
                    params.setMaxComponents(maxComponents);
                    params.setIncludeAttributes(includeAttributes);
                    params.setIncludeOutgoingVariants(includeOutgoingVariants);
                    params.setMaxConfigurations(maxConfigurations);
                }
        );
    }
}
