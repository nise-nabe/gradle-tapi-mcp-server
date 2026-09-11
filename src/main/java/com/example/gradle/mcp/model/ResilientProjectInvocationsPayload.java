package com.example.gradle.mcp.model;

import org.gradle.tooling.Failure;
import org.gradle.tooling.FetchModelResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Combined {@code GradleProject} + {@code BuildInvocations} result from one phased BuildAction.
 */
public final class ResilientProjectInvocationsPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Object project;
    private final Object invocations;
    private final ArrayList<FailureRecord> failures;
    private final boolean failuresTruncated;

    public ResilientProjectInvocationsPayload(
            Object project,
            Object invocations,
            List<FailureRecord> failures,
            boolean failuresTruncated
    ) {
        this.project = project;
        this.invocations = invocations;
        this.failures = failures == null ? new ArrayList<>() : new ArrayList<>(failures);
        this.failuresTruncated = failuresTruncated;
    }

    public static ResilientProjectInvocationsPayload from(
            FetchModelResult<?> project,
            FetchModelResult<?> invocations
    ) {
        ArrayList<Failure> all = new ArrayList<>();
        addFailures(all, project);
        addFailures(all, invocations);
        FailureRecords.Slice slice = FailureRecords.fromFailures(all);
        return new ResilientProjectInvocationsPayload(
                project == null ? null : project.getModel(),
                invocations == null ? null : invocations.getModel(),
                slice.getRecords(),
                slice.isTruncated()
        );
    }

    public Object getProject() {
        return project;
    }

    public Object getInvocations() {
        return invocations;
    }

    public List<FailureRecord> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    public boolean isFailuresTruncated() {
        return failuresTruncated;
    }

    private static void addFailures(List<Failure> into, FetchModelResult<?> result) {
        if (result == null) {
            return;
        }
        Collection<? extends Failure> failures = result.getFailures();
        if (failures != null && !failures.isEmpty()) {
            into.addAll(failures);
        }
    }
}
