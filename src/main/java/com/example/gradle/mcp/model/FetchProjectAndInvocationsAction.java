package com.example.gradle.mcp.model;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.gradle.BuildInvocations;

import java.io.Serializable;

/**
 * Fetches {@link GradleProject} and {@link BuildInvocations} in one {@code buildFinished} action
 * so both models share the same resilient {@code fetch} operation.
 */
public final class FetchProjectAndInvocationsAction
        implements BuildAction<ResilientProjectInvocationsPayload>, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public ResilientProjectInvocationsPayload execute(BuildController controller) {
        return ResilientProjectInvocationsPayload.from(
                controller.fetch(GradleProject.class),
                controller.fetch(BuildInvocations.class)
        );
    }
}
