package com.example.gradle.mcp.support

import com.example.gradle.mcp.build.FailedTestSnapshot
import com.example.gradle.mcp.build.ProgressEventSnapshot
import com.example.gradle.mcp.build.ProgressEventTypes
import com.example.gradle.mcp.build.TestProgressDetailsSnapshot

internal fun failedTestSnapshot(
    className: String,
    methodName: String,
    failureMessage: String,
    displayName: String = "$className.$methodName",
): FailedTestSnapshot =
    FailedTestSnapshot(
        className = className,
        methodName = methodName,
        displayName = displayName,
        failureMessage = failureMessage,
    )

internal fun testFailProgressEvent(
    className: String,
    methodName: String,
    failureMessage: String,
    timestamp: String = TEST_ISO_START,
): ProgressEventSnapshot =
    ProgressEventSnapshot(
        timestamp = timestamp,
        eventType = ProgressEventTypes.TEST_FAIL,
        displayName = "$className.$methodName",
        outcome = failureMessage,
        testDetails = TestProgressDetailsSnapshot(
            className = className,
            methodName = methodName,
            failureMessage = failureMessage,
        ),
    )
