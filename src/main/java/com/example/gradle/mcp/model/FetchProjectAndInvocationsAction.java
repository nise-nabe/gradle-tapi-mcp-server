package com.example.gradle.mcp.model;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.FetchModelResult;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.gradle.BasicGradleProject;
import org.gradle.tooling.model.gradle.BuildInvocations;
import org.gradle.tooling.model.gradle.GradleBuild;

import java.io.Serializable;

/**
 * Fetches {@link GradleProject} and {@link BuildInvocations} in one {@code buildFinished} action
 * so both models share the same resilient {@code fetch} operation.
 */
public final class FetchProjectAndInvocationsAction
        implements BuildAction<ResilientProjectInvocationsPayload>, Serializable {
    private static final long serialVersionUID = 2L;

    private final String buildTreePath;

    public FetchProjectAndInvocationsAction() {
        this(null);
    }

    public FetchProjectAndInvocationsAction(String buildTreePath) {
        this.buildTreePath = blankToNull(buildTreePath);
    }

    public String getBuildTreePath() {
        return buildTreePath;
    }

    @Override
    public ResilientProjectInvocationsPayload execute(BuildController controller) {
        if (buildTreePath == null) {
            return ResilientProjectInvocationsPayload.from(
                    controller.fetch(GradleProject.class),
                    controller.fetch(BuildInvocations.class)
            );
        }
        FetchModelResult<?> gradleBuildResult = controller.fetch(GradleBuild.class);
        Object rawBuild = gradleBuildResult.getModel();
        if (!(rawBuild instanceof GradleBuild gradleBuild)) {
            return ResilientProjectInvocationsPayload.missingModels(gradleBuildResult);
        }
        BasicGradleProject target = BuildTreeTargetLookup.find(gradleBuild, buildTreePath);
        if (target == null) {
            return ResilientProjectInvocationsPayload.unresolvedTarget(gradleBuildResult, buildTreePath);
        }
        return ResilientProjectInvocationsPayload.fromTargeted(
                gradleBuildResult,
                controller.fetch(target, GradleProject.class),
                controller.fetch(target, BuildInvocations.class)
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
