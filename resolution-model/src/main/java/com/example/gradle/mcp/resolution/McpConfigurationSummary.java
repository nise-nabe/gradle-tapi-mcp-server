package com.example.gradle.mcp.resolution;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Token-efficient configuration catalog entry. Declarable-only configurations are omitted
 * unless they are also resolvable or consumable.
 */
public interface McpConfigurationSummary extends Serializable {
    String getName();

    boolean isCanBeResolved();

    boolean isCanBeConsumed();

    String getDescription();

    Map<String, String> getAttributes();

    List<McpOutgoingVariant> getOutgoingVariants();
}
