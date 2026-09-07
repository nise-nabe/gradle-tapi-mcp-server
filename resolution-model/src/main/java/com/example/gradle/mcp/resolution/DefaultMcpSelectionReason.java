package com.example.gradle.mcp.resolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DefaultMcpSelectionReason implements McpSelectionReason {
    private final List<String> descriptions;
    private final boolean conflictResolution;
    private final boolean constrained;
    private final boolean expected;
    private final boolean forced;
    private final boolean selectedByRule;
    private final boolean compositeSubstitution;

    public DefaultMcpSelectionReason(
            List<String> descriptions,
            boolean conflictResolution,
            boolean constrained,
            boolean expected,
            boolean forced,
            boolean selectedByRule,
            boolean compositeSubstitution
    ) {
        this.descriptions = Collections.unmodifiableList(new ArrayList<>(descriptions));
        this.conflictResolution = conflictResolution;
        this.constrained = constrained;
        this.expected = expected;
        this.forced = forced;
        this.selectedByRule = selectedByRule;
        this.compositeSubstitution = compositeSubstitution;
    }

    @Override
    public List<String> getDescriptions() {
        return descriptions;
    }

    @Override
    public boolean isConflictResolution() {
        return conflictResolution;
    }

    @Override
    public boolean isConstrained() {
        return constrained;
    }

    @Override
    public boolean isExpected() {
        return expected;
    }

    @Override
    public boolean isForced() {
        return forced;
    }

    @Override
    public boolean isSelectedByRule() {
        return selectedByRule;
    }

    @Override
    public boolean isCompositeSubstitution() {
        return compositeSubstitution;
    }
}
