package com.example.gradle.mcp.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Daemon-safe snapshot of a Tooling API {@code Failure}. Strings only; nested causes are pre-capped.
 */
public final class FailureRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String message;
    private final String description;
    private final ArrayList<FailureRecord> causes;
    private final ArrayList<String> problems;

    public FailureRecord(
            String message,
            String description,
            List<FailureRecord> causes,
            List<String> problems
    ) {
        this.message = message;
        this.description = description;
        this.causes = copy(causes);
        this.problems = copyStrings(problems);
    }

    public String getMessage() {
        return message;
    }

    public String getDescription() {
        return description;
    }

    public List<FailureRecord> getCauses() {
        return Collections.unmodifiableList(causes);
    }

    public List<String> getProblems() {
        return Collections.unmodifiableList(problems);
    }

    public List<String> getCauseMessages() {
        ArrayList<String> messages = new ArrayList<>();
        collectCauseMessages(causes, messages);
        return Collections.unmodifiableList(messages);
    }

    private static void collectCauseMessages(List<FailureRecord> nested, List<String> into) {
        for (FailureRecord cause : nested) {
            if (into.size() >= FailureRecords.MAX_CAUSE_MESSAGES) {
                return;
            }
            if (cause.message != null && !cause.message.isBlank()) {
                into.add(cause.message);
            }
            collectCauseMessages(cause.causes, into);
        }
    }

    private static ArrayList<FailureRecord> copy(List<FailureRecord> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values);
    }

    private static ArrayList<String> copyStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values);
    }
}
