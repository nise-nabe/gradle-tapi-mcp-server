package com.example.gradle.mcp.resolution;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;

import java.io.Serializable;

/**
 * Fetches {@link McpDependencyResolution} with parameters. The model builder is registered
 * by {@link McpDependencyResolutionPlugin} via an MCP-owned init script.
 */
public final class FetchDependencyResolutionAction
        implements BuildAction<McpDependencyResolution>, Serializable {
    private static final long serialVersionUID = 1L;

    private final String projectPath;
    private final String configuration;
    private final String dependency;
    private final int maxDependencies;
    private final int maxComponents;

    public FetchDependencyResolutionAction(
            String projectPath,
            String configuration,
            String dependency,
            int maxDependencies,
            int maxComponents
    ) {
        this.projectPath = projectPath;
        this.configuration = configuration;
        this.dependency = dependency;
        this.maxDependencies = maxDependencies;
        this.maxComponents = maxComponents;
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
                }
        );
    }
}
