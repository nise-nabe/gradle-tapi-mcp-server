package com.example.gradle.mcp.model;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.FetchModelResult;
import org.gradle.tooling.model.gradle.BasicGradleProject;
import org.gradle.tooling.model.gradle.GradleBuild;

import java.io.Serializable;
import java.util.Objects;

/**
 * Fetches one Tooling API model via {@link BuildController#fetch(Class)} so Gradle can return
 * a partial model plus failures instead of failing the whole operation.
 *
 * <p>When {@code buildTreePath} is set, locates that project in the {@link GradleBuild} tree
 * (included builds and {@code buildSrc}) and uses {@link BuildController#fetch(org.gradle.tooling.model.Model, Class)}.
 */
public final class FetchToolingModelAction implements BuildAction<ResilientModelPayload>, Serializable {
    private static final long serialVersionUID = 2L;

    private final Class<?> modelType;
    private final String buildTreePath;

    public FetchToolingModelAction(Class<?> modelType) {
        this(modelType, null);
    }

    public FetchToolingModelAction(Class<?> modelType, String buildTreePath) {
        this.modelType = Objects.requireNonNull(modelType, "modelType");
        this.buildTreePath = blankToNull(buildTreePath);
    }

    public Class<?> getModelType() {
        return modelType;
    }

    public String getBuildTreePath() {
        return buildTreePath;
    }

    @Override
    public ResilientModelPayload execute(BuildController controller) {
        if (buildTreePath == null) {
            return ResilientModelPayload.from(controller.fetch(modelType));
        }
        FetchModelResult<?> gradleBuildResult = controller.fetch(GradleBuild.class);
        Object rawBuild = gradleBuildResult.getModel();
        if (!(rawBuild instanceof GradleBuild gradleBuild)) {
            return ResilientModelPayload.from(gradleBuildResult);
        }
        BasicGradleProject target = BuildTreeTargetLookup.find(gradleBuild, buildTreePath);
        if (target == null) {
            return ResilientModelPayload.unresolvedTarget(gradleBuildResult, buildTreePath);
        }
        FetchModelResult<?> modelResult = controller.fetch(target, modelType);
        return ResilientModelPayload.fromTargeted(gradleBuildResult, modelResult);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
