package com.example.gradle.mcp.resolution;

public final class DefaultMcpOutgoingArtifact implements McpOutgoingArtifact {
    private final String name;
    private final String extension;
    private final String classifier;
    private final String type;

    public DefaultMcpOutgoingArtifact(String name, String extension, String classifier, String type) {
        this.name = name;
        this.extension = extension;
        this.classifier = classifier;
        this.type = type;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getExtension() {
        return extension;
    }

    @Override
    public String getClassifier() {
        return classifier;
    }

    @Override
    public String getType() {
        return type;
    }
}
