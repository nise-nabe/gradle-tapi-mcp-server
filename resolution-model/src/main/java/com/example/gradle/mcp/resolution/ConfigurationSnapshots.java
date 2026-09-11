package com.example.gradle.mcp.resolution;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationPublications;
import org.gradle.api.artifacts.ConfigurationVariant;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.capabilities.Capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Gradle {@link Configuration} objects into serializable catalog entries.
 * Does not include local file paths.
 */
final class ConfigurationSnapshots {
    private ConfigurationSnapshots() {
    }

    static McpConfigurationSummary from(
            Configuration configuration,
            boolean includeAttributes,
            boolean includeOutgoingVariants
    ) {
        Map<String, String> attributes = includeAttributes
                ? attributesMap(configuration.getAttributes())
                : Map.of();
        List<McpOutgoingVariant> variants = includeOutgoingVariants
                ? outgoingVariants(configuration)
                : List.of();
        return new DefaultMcpConfigurationSummary(
                configuration.getName(),
                configuration.isCanBeResolved(),
                configuration.isCanBeConsumed(),
                blankToNull(configuration.getDescription()),
                attributes,
                variants
        );
    }

    private static List<McpOutgoingVariant> outgoingVariants(Configuration configuration) {
        ConfigurationPublications outgoing = configuration.getOutgoing();
        List<McpOutgoingArtifact> implicitArtifacts = artifacts(outgoing.getArtifacts());
        boolean hasExtraVariants = !outgoing.getVariants().isEmpty();
        if (!configuration.isCanBeConsumed() && implicitArtifacts.isEmpty() && !hasExtraVariants) {
            return List.of();
        }
        List<McpOutgoingVariant> variants = new ArrayList<>();
        variants.add(
                new DefaultMcpOutgoingVariant(
                        configuration.getName(),
                        attributesMap(configuration.getAttributes()),
                        capabilities(outgoing),
                        implicitArtifacts
                )
        );
        for (ConfigurationVariant variant : outgoing.getVariants()) {
            variants.add(
                    new DefaultMcpOutgoingVariant(
                            variant.getName(),
                            attributesMap(variant.getAttributes()),
                            List.of(),
                            artifacts(variant.getArtifacts())
                    )
            );
        }
        return variants;
    }

    private static List<McpOutgoingArtifact> artifacts(Iterable<? extends PublishArtifact> publishArtifacts) {
        List<McpOutgoingArtifact> artifacts = new ArrayList<>();
        for (PublishArtifact artifact : publishArtifacts) {
            artifacts.add(
                    new DefaultMcpOutgoingArtifact(
                            artifact.getName(),
                            artifact.getExtension(),
                            artifact.getClassifier(),
                            artifact.getType()
                    )
            );
        }
        return artifacts;
    }

    private static List<String> capabilities(ConfigurationPublications outgoing) {
        List<String> capabilities = new ArrayList<>();
        for (Capability capability : outgoing.getCapabilities()) {
            String group = capability.getGroup() == null ? "" : capability.getGroup();
            String name = capability.getName() == null ? "" : capability.getName();
            String version = capability.getVersion() == null ? "" : capability.getVersion();
            capabilities.add(group + ":" + name + ":" + version);
        }
        return capabilities;
    }

    private static Map<String, String> attributesMap(AttributeContainer attributes) {
        Map<String, String> mapped = new LinkedHashMap<>();
        if (attributes == null) {
            return mapped;
        }
        for (Attribute<?> attribute : attributes.keySet()) {
            Object value = attributes.getAttribute(attribute);
            if (value != null) {
                mapped.put(attribute.getName(), String.valueOf(value));
            }
        }
        return mapped;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
