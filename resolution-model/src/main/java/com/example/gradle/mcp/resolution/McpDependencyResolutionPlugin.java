package com.example.gradle.mcp.resolution;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;

/**
 * Registers the {@link McpDependencyResolution} tooling model. Applied via MCP init script;
 * target builds do not need to declare this plugin.
 */
public class McpDependencyResolutionPlugin implements Plugin<Project> {
    private final ToolingModelBuilderRegistry registry;

    @Inject
    public McpDependencyResolutionPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(Project project) {
        if (project.getParent() == null) {
            registry.register(new McpDependencyResolutionModelBuilder());
        }
    }
}
