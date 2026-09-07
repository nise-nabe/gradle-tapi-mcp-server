package com.example.gradle.mcp.resolution;

public final class DefaultMcpResolvedDependencyEdge implements McpResolvedDependencyEdge {
    private final McpResolvedComponentIdentity from;
    private final String requested;
    private final McpResolvedComponentIdentity selected;
    private final boolean resolved;
    private final String failureMessage;
    private final McpSelectionReason selectionReason;

    public DefaultMcpResolvedDependencyEdge(
            McpResolvedComponentIdentity from,
            String requested,
            McpResolvedComponentIdentity selected,
            boolean resolved,
            String failureMessage,
            McpSelectionReason selectionReason
    ) {
        this.from = from;
        this.requested = requested;
        this.selected = selected;
        this.resolved = resolved;
        this.failureMessage = failureMessage;
        this.selectionReason = selectionReason;
    }

    @Override
    public McpResolvedComponentIdentity getFrom() {
        return from;
    }

    @Override
    public String getRequested() {
        return requested;
    }

    @Override
    public McpResolvedComponentIdentity getSelected() {
        return selected;
    }

    @Override
    public boolean isResolved() {
        return resolved;
    }

    @Override
    public String getFailureMessage() {
        return failureMessage;
    }

    @Override
    public McpSelectionReason getSelectionReason() {
        return selectionReason;
    }
}
