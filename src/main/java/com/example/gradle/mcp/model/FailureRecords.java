package com.example.gradle.mcp.model;

import org.gradle.tooling.Failure;
import org.gradle.tooling.FetchModelResult;
import org.gradle.tooling.events.problems.Problem;
import org.gradle.tooling.events.problems.ProblemDefinition;
import org.gradle.tooling.events.problems.ProblemId;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Caps and flattens Tooling API failures for MCP payloads (daemon and client).
 */
public final class FailureRecords {
    public static final int MAX_FAILURES = 20;
    public static final int MAX_CAUSE_DEPTH = 2;
    public static final int MAX_CAUSES_PER_FAILURE = 5;
    public static final int MAX_CAUSE_MESSAGES = 10;
    public static final int MAX_MESSAGE_CHARS = 400;
    public static final int MAX_PROBLEMS_PER_FAILURE = 5;

    private FailureRecords() {
    }

    public static Slice fromModelResults(FetchModelResult<?>... results) {
        if (results == null || results.length == 0) {
            return Slice.empty();
        }
        ArrayList<Failure> all = new ArrayList<>();
        for (FetchModelResult<?> result : results) {
            if (result == null) {
                continue;
            }
            Collection<? extends Failure> failures;
            try {
                failures = result.getFailures();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (failures != null && !failures.isEmpty()) {
                all.addAll(failures);
            }
        }
        return fromFailures(all);
    }

    public static Slice fromFailures(Collection<? extends Failure> failures) {
        if (failures == null || failures.isEmpty()) {
            return Slice.empty();
        }
        ArrayList<FailureRecord> records = new ArrayList<>();
        int seen = 0;
        for (Failure failure : failures) {
            seen++;
            if (records.size() >= MAX_FAILURES) {
                continue;
            }
            records.add(fromFailure(failure, 0));
        }
        return new Slice(records, seen > MAX_FAILURES);
    }

    static String truncate(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_MESSAGE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_MESSAGE_CHARS);
    }

    private static FailureRecord fromFailure(Failure failure, int depth) {
        String message = truncate(safeMessage(failure));
        String description = truncate(safeDescription(failure));
        ArrayList<FailureRecord> causes = new ArrayList<>();
        if (depth < MAX_CAUSE_DEPTH) {
            Collection<? extends Failure> nested = safeCauses(failure);
            for (Failure cause : nested) {
                if (causes.size() >= MAX_CAUSES_PER_FAILURE) {
                    break;
                }
                causes.add(fromFailure(cause, depth + 1));
            }
        }
        List<String> problems = problemLabels(failure);
        if (message == null && description == null && causes.isEmpty() && problems.isEmpty()) {
            message = "Unknown failure";
        }
        return new FailureRecord(message, description, causes, problems);
    }

    private static String safeMessage(Failure failure) {
        try {
            return failure.getMessage();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeDescription(Failure failure) {
        try {
            return failure.getDescription();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Collection<? extends Failure> safeCauses(Failure failure) {
        try {
            List<? extends Failure> causes = failure.getCauses();
            return causes == null ? List.of() : causes;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<String> problemLabels(Failure failure) {
        Collection<? extends Problem> problems;
        try {
            problems = failure.getProblems();
        } catch (RuntimeException ignored) {
            return List.of();
        }
        if (problems == null || problems.isEmpty()) {
            return List.of();
        }
        ArrayList<String> labels = new ArrayList<>();
        for (Problem problem : problems) {
            if (labels.size() >= MAX_PROBLEMS_PER_FAILURE) {
                break;
            }
            String label = problemLabel(problem);
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return labels;
    }

    private static String problemLabel(Problem problem) {
        try {
            ProblemDefinition definition = problem.getDefinition();
            if (definition == null) {
                return null;
            }
            ProblemId id = definition.getId();
            if (id == null) {
                return null;
            }
            String displayName = id.getDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return truncate(displayName);
            }
            return truncate(id.getName());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static final class Slice implements Serializable {
        private static final long serialVersionUID = 1L;

        private final ArrayList<FailureRecord> records;
        private final boolean truncated;

        Slice(ArrayList<FailureRecord> records, boolean truncated) {
            this.records = records;
            this.truncated = truncated;
        }

        static Slice empty() {
            return new Slice(new ArrayList<>(), false);
        }

        public List<FailureRecord> getRecords() {
            return Collections.unmodifiableList(records);
        }

        public boolean isTruncated() {
            return truncated;
        }
    }
}
