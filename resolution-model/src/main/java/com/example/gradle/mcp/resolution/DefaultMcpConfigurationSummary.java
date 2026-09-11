package com.example.gradle.mcp.resolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultMcpConfigurationSummary implements McpConfigurationSummary {
    private final String name;
    private final boolean canBeResolved;
    private final boolean canBeConsumed;
    private final String description;
    private final Map<String, String> attributes;
    private final List<McpOutgoingVariant> outgoingVariants;

    public DefaultMcpConfigurationSummary(
            String name,
            boolean canBeResolved,
            boolean canBeConsumed,
            String description,
            Map<String, String> attributes,
            List<McpOutgoingVariant> outgoingVariants
    ) {
        this.name = name;
        this.canBeResolved = canBeResolved;
        this.canBeConsumed = canBeConsumed;
        this.description = description;
        this.attributes = immutableMap(attributes);
        this.outgoingVariants = immutable(outgoingVariants);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isCanBeResolved() {
        return canBeResolved;
    }

    @Override
    public boolean isCanBeConsumed() {
        return canBeConsumed;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public List<McpOutgoingVariant> getOutgoingVariants() {
        return outgoingVariants;
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
