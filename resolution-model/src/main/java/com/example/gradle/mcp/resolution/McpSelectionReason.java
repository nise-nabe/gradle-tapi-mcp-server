package com.example.gradle.mcp.resolution;

import java.io.Serializable;
import java.util.List;

public interface McpSelectionReason extends Serializable {
    List<String> getDescriptions();

    boolean isConflictResolution();

    boolean isConstrained();

    boolean isExpected();

    boolean isForced();

    boolean isSelectedByRule();

    boolean isCompositeSubstitution();
}
