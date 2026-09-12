package com.example.gradle.mcp.model;

import org.gradle.tooling.FetchModelResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BuildAction result for a single Tooling API model. Holds the model plus capped failure records
 * because {@link FetchModelResult} itself is not a daemon-safe return type.
 */
public final class ResilientModelPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Object model;
    private final ArrayList<FailureRecord> failures;
    private final boolean failuresTruncated;

    public ResilientModelPayload(Object model, List<FailureRecord> failures, boolean failuresTruncated) {
        this.model = model;
        this.failures = failures == null ? new ArrayList<>() : new ArrayList<>(failures);
        this.failuresTruncated = failuresTruncated;
    }

    public static ResilientModelPayload from(FetchModelResult<?> result) {
        if (result == null) {
            return new ResilientModelPayload(null, List.of(), false);
        }
        FailureRecords.Slice slice = FailureRecords.fromFailures(result.getFailures());
        return new ResilientModelPayload(result.getModel(), slice.getRecords(), slice.isTruncated());
    }

    public Object getModel() {
        return model;
    }

    public List<FailureRecord> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    public boolean isFailuresTruncated() {
        return failuresTruncated;
    }
}
