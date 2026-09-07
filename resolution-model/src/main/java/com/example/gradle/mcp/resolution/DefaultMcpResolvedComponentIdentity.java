package com.example.gradle.mcp.resolution;

import java.io.Serializable;

public final class DefaultMcpResolvedComponentIdentity implements McpResolvedComponentIdentity {
    private final String displayName;
    private final String group;
    private final String module;
    private final String version;

    public DefaultMcpResolvedComponentIdentity(String displayName, String group, String module, String version) {
        this.displayName = displayName;
        this.group = group;
        this.module = module;
        this.version = version;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public String getModule() {
        return module;
    }

    @Override
    public String getVersion() {
        return version;
    }
}
