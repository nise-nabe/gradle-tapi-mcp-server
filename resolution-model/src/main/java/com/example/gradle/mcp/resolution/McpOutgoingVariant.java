package com.example.gradle.mcp.resolution;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * A consumable outgoing variant (implicit configuration variant or an extra named variant).
 */
public interface McpOutgoingVariant extends Serializable {
    String getName();

    Map<String, String> getAttributes();

    List<String> getCapabilities();

    List<McpOutgoingArtifact> getArtifacts();
}
