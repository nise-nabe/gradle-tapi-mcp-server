package com.example.gradle.mcp.resolution;

import java.io.Serializable;

/**
 * Publish artifact coordinates without local file paths.
 */
public interface McpOutgoingArtifact extends Serializable {
    String getName();

    String getExtension();

    String getClassifier();

    String getType();
}
