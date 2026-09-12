package com.example.gradle.mcp.model;

import org.gradle.tooling.FetchModelResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Combined {@code GradleProject} + {@code BuildInvocations} result from one phased BuildAction.
 */
public final class ResilientProjectInvocationsPayload implements Serializable {
    private static final long serialVersionUID = 2L;

    private final Object project;
    private final Object invocations;
    private final ArrayList<FailureRecord> failures;
    private final boolean failuresTruncated;
    private final String unresolvedBuildTreePath;

    public ResilientProjectInvocationsPayload(
            Object project,
            Object invocations,
            List<FailureRecord> failures,
            boolean failuresTruncated
    ) {
        this(project, invocations, failures, failuresTruncated, null);
    }

    public ResilientProjectInvocationsPayload(
            Object project,
            Object invocations,
            List<FailureRecord> failures,
            boolean failuresTruncated,
            String unresolvedBuildTreePath
    ) {
        this.project = project;
        this.invocations = invocations;
        this.failures = failures == null ? new ArrayList<>() : new ArrayList<>(failures);
        this.failuresTruncated = failuresTruncated;
        this.unresolvedBuildTreePath = unresolvedBuildTreePath;
    }

    public static ResilientProjectInvocationsPayload from(
            FetchModelResult<?> project,
            FetchModelResult<?> invocations
    ) {
        FailureRecords.Slice slice = FailureRecords.fromModelResults(project, invocations);
        return new ResilientProjectInvocationsPayload(
                project == null ? null : project.getModel(),
                invocations == null ? null : invocations.getModel(),
                slice.getRecords(),
                slice.isTruncated()
        );
    }

    public static ResilientProjectInvocationsPayload fromTargeted(
            FetchModelResult<?> gradleBuildResult,
            FetchModelResult<?> project,
            FetchModelResult<?> invocations
    ) {
        FailureRecords.Slice slice = FailureRecords.fromModelResults(project, invocations, gradleBuildResult);
        return new ResilientProjectInvocationsPayload(
                project == null ? null : project.getModel(),
                invocations == null ? null : invocations.getModel(),
                slice.getRecords(),
                slice.isTruncated()
        );
    }

    public static ResilientProjectInvocationsPayload missingModels(FetchModelResult<?> gradleBuildResult) {
        FailureRecords.Slice slice = FailureRecords.fromModelResults(gradleBuildResult);
        return new ResilientProjectInvocationsPayload(
                null,
                null,
                slice.getRecords(),
                slice.isTruncated()
        );
    }

    public static ResilientProjectInvocationsPayload unresolvedTarget(
            FetchModelResult<?> gradleBuildResult,
            String buildTreePath
    ) {
        FailureRecords.Slice slice = FailureRecords.fromModelResults(gradleBuildResult);
        return new ResilientProjectInvocationsPayload(
                null,
                null,
                slice.getRecords(),
                slice.isTruncated(),
                buildTreePath
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

    public String getUnresolvedBuildTreePath() {
        return unresolvedBuildTreePath;
    }
}
