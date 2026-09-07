package com.example.gradle.mcp.resolution;

import java.io.Serializable;

public interface McpResolvedComponent extends Serializable {
    McpResolvedComponentIdentity getId();

    String getVariantName();

    McpSelectionReason getSelectionReason();
}
