package com.example.gradle.mcp.model;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.FetchModelResult;

import java.io.Serializable;
import java.util.Objects;

/**
 * Fetches one Tooling API model via {@link BuildController#fetch(Class)} so Gradle can return
 * a partial model plus failures instead of failing the whole operation.
 */
public final class FetchToolingModelAction implements BuildAction<ResilientModelPayload>, Serializable {
    private static final long serialVersionUID = 1L;

    private final Class<?> modelType;

    public FetchToolingModelAction(Class<?> modelType) {
        this.modelType = Objects.requireNonNull(modelType, "modelType");
    }

    public Class<?> getModelType() {
        return modelType;
    }

    @Override
    public ResilientModelPayload execute(BuildController controller) {
        FetchModelResult<?> result = controller.fetch(modelType);
        return ResilientModelPayload.from(result);
    }
}
