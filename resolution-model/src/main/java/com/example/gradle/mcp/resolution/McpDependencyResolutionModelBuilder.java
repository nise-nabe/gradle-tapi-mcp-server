package com.example.gradle.mcp.resolution;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;

import java.util.Objects;

final class McpDependencyResolutionModelBuilder
        implements ParameterizedToolingModelBuilder<McpDependencyResolutionParams> {

    @Override
    public boolean canBuild(String modelName) {
        return McpDependencyResolution.class.getName().equals(modelName);
    }

    @Override
    public Class<McpDependencyResolutionParams> getParameterType() {
        return McpDependencyResolutionParams.class;
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        throw new IllegalArgumentException(
                "McpDependencyResolution requires parameters (configuration is required). " +
                        "Use a parameterized tooling model request."
        );
    }

    @Override
    public Object buildAll(String modelName, McpDependencyResolutionParams parameter, Project project) {
        Objects.requireNonNull(parameter, "parameter");
        String configurationName = requireConfiguration(parameter.getConfiguration());
        Project target = resolveProject(project, parameter.getProjectPath());
        Configuration configuration = findResolvableConfiguration(target, configurationName);
        ResolutionResult resolutionResult = configuration.getIncoming().getResolutionResult();
        return ResolutionResultMapper.map(
                target.getPath(),
                configuration.getName(),
                parameter.getDependency(),
                resolutionResult,
                parameter.getMaxDependencies(),
                parameter.getMaxComponents()
        );
    }

    private static String requireConfiguration(String configuration) {
        if (configuration == null || configuration.isBlank()) {
            throw new IllegalArgumentException(
                    "configuration is required (e.g. runtimeClasspath, compileClasspath)"
            );
        }
        return configuration.trim();
    }

    private static Project resolveProject(Project project, String projectPath) {
        if (projectPath == null || projectPath.isBlank() || ":".equals(projectPath.trim())) {
            return project.getRootProject();
        }
        String path = projectPath.trim();
        Project found = project.getRootProject().findProject(path);
        if (found == null) {
            throw new IllegalArgumentException("Unknown projectPath: " + path);
        }
        return found;
    }

    private static Configuration findResolvableConfiguration(Project project, String configurationName) {
        Configuration configuration = project.getConfigurations().findByName(configurationName);
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "Unknown configuration '" + configurationName + "' on project " + project.getPath()
            );
        }
        if (!configuration.isCanBeResolved()) {
            throw new IllegalArgumentException(
                    "Configuration '" + configurationName + "' on " + project.getPath() +
                            " is not resolvable (canBeResolved=false). " +
                            "Use a resolvable configuration such as runtimeClasspath or compileClasspath."
            );
        }
        return configuration;
    }
}
