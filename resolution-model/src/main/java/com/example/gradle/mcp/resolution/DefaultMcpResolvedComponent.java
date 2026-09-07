package com.example.gradle.mcp.resolution;

public final class DefaultMcpResolvedComponent implements McpResolvedComponent {
    private final McpResolvedComponentIdentity id;
    private final String variantName;
    private final McpSelectionReason selectionReason;

    public DefaultMcpResolvedComponent(
            McpResolvedComponentIdentity id,
            String variantName,
            McpSelectionReason selectionReason
    ) {
        this.id = id;
        this.variantName = variantName;
        this.selectionReason = selectionReason;
    }

    @Override
    public McpResolvedComponentIdentity getId() {
        return id;
    }

    @Override
    public String getVariantName() {
        return variantName;
    }

    @Override
    public McpSelectionReason getSelectionReason() {
        return selectionReason;
    }
}
