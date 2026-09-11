package com.example.gradle.mcp.resolution;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;

import java.util.ArrayList;
import java.util.List;
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
                "McpDependencyResolution requires parameters. " +
                        "Use a parameterized tooling model request; omit configuration to list names."
        );
    }

    @Override
    public Object buildAll(String modelName, McpDependencyResolutionParams parameter, Project project) {
        Objects.requireNonNull(parameter, "parameter");
        Project target = resolveProject(project, parameter.getProjectPath());
        String configurationName = trimToNull(parameter.getConfiguration());
        if (configurationName == null) {
            return catalog(
                    target,
                    parameter.getIncludeAttributes(),
                    parameter.getIncludeOutgoingVariants(),
                    parameter.getMaxConfigurations()
            );
        }
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

    static DefaultMcpDependencyResolution catalog(
            Project project,
            boolean includeAttributes,
            boolean includeOutgoingVariants,
            int maxConfigurations
    ) {
        List<McpConfigurationSummary> all = new ArrayList<>();
        for (Configuration configuration : project.getConfigurations()) {
            all.add(ConfigurationSnapshots.from(configuration, includeAttributes, includeOutgoingVariants));
        }
        List<McpConfigurationSummary> selected = ConfigurationCatalogMapper.selectResolvableOrConsumable(all);
        ConfigurationCatalogMapper.CatalogSlice slice =
                ConfigurationCatalogMapper.cap(selected, maxConfigurations);
        return DefaultMcpDependencyResolution.catalog(
                project.getPath(),
                slice.configurations,
                slice.truncated,
                slice.totalCount
        );
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
        List<McpConfigurationSummary> catalog = summariesForSuggestions(project);
        String suffix = ConfigurationCatalogMapper.resolvableSuffix(
                ConfigurationCatalogMapper.resolvableNames(catalog)
        );
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "Unknown configuration '" + configurationName + "' on project " + project.getPath() +
                            ". Omit configuration to list names. " + suffix
            );
        }
        if (!configuration.isCanBeResolved()) {
            throw new IllegalArgumentException(
                    "Configuration '" + configurationName + "' on " + project.getPath() +
                            " is not resolvable (canBeResolved=false). " +
                            "Omit configuration to list names. " + suffix
            );
        }
        return configuration;
    }

    private static List<McpConfigurationSummary> summariesForSuggestions(Project project) {
        List<McpConfigurationSummary> all = new ArrayList<>();
        for (Configuration configuration : project.getConfigurations()) {
            all.add(ConfigurationSnapshots.from(configuration, false, false));
        }
        return all;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
