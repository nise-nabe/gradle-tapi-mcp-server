package com.example.gradle.mcp.resolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultMcpOutgoingVariant implements McpOutgoingVariant {
    private final String name;
    private final Map<String, String> attributes;
    private final List<String> capabilities;
    private final List<McpOutgoingArtifact> artifacts;

    public DefaultMcpOutgoingVariant(
            String name,
            Map<String, String> attributes,
            List<String> capabilities,
            List<McpOutgoingArtifact> artifacts
    ) {
        this.name = name;
        this.attributes = immutableMap(attributes);
        this.capabilities = immutable(capabilities);
        this.artifacts = immutable(artifacts);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public List<String> getCapabilities() {
        return capabilities;
    }

    @Override
    public List<McpOutgoingArtifact> getArtifacts() {
        return artifacts;
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static Map<String, String> immutableMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
