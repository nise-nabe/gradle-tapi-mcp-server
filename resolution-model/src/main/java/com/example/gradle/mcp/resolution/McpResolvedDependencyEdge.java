package com.example.gradle.mcp.resolution;

import java.io.Serializable;

public interface McpResolvedDependencyEdge extends Serializable {
    McpResolvedComponentIdentity getFrom();

    String getRequested();

    McpResolvedComponentIdentity getSelected();

    boolean isResolved();

    String getFailureMessage();

    McpSelectionReason getSelectionReason();
}
