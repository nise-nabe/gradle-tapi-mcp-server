package com.example.gradle.mcp.resolution;

import java.io.Serializable;

public interface McpResolvedComponentIdentity extends Serializable {
    String getDisplayName();

    String getGroup();

    String getModule();

    String getVersion();
}
