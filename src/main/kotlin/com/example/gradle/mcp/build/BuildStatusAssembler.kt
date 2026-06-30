package com.example.gradle.mcp.build

import com.example.gradle.mcp.model.OutputLimitOptions
import com.example.gradle.mcp.protocol.ProgressResponseOptions
import com.example.gradle.mcp.protocol.optionalDownloadFields
import com.example.gradle.mcp.protocol.optionalProgressFields
import com.example.gradle.mcp.protocol.terminalFailureFields

internal enum class BuildStatusResponseStyle {
    STATUS_POLL,
    FOREGROUND,
}

internal object BuildStatusAssembler {
    fun assemble(
        view: BuildStatusView,
        outputLimit: OutputLimitOptions,
        progressOptions: ProgressResponseOptions,
        style: BuildStatusResponseStyle = BuildStatusResponseStyle.STATUS_POLL,
    ): Map<String, Any?> {
        val isRunning = view.status == BuildProgressTracker.STATUS_RUNNING
        val response = mutableMapOf<String, Any?>()

        if (style == BuildStatusResponseStyle.FOREGROUND) {
            when (view.kind) {
                "tasks" -> response["tasks"] = view.tasks
                "tests" -> {
                    response["testClasses"] = view.testClasses
                    response.putTestRunSelection(view.selection)
                }
            }
        }

        if (style == BuildStatusResponseStyle.STATUS_POLL) {
            response["buildId"] = view.buildId
            response["startedAt"] = view.startedAt
            response["finishedAt"] = view.finishedAt
            response["tasks"] = view.tasks
            response["testClasses"] = view.testClasses
            response.putTestRunSelection(view.selection)
            response["statusSource"] = view.statusSource
        }

        response["status"] = view.status
        view.kind?.let { response["kind"] = it }
        if (view.statusSource == BuildStatusView.SOURCE_DISK) {
            view.recordDirectory?.let { response["recordDirectory"] = it }
            response["liveProgress"] = false
            response["progressAvailable"] = view.progressAvailable
        }
        view.error?.let { response["error"] = it }
        view.outcome?.let { response["outcome"] = it }

        if (progressOptions.includeProgress && view.progressAvailable && view.progress != null) {
            response.putAll(optionalProgressFields(progressOptions, view.progress, view.statusSource))
        }

        view.progress?.let { progress ->
            response.putAll(optionalDownloadFields(progressOptions, progress, view.statusSource))
        }

        if (!isRunning) {
            view.buildSummary?.let { response["buildSummary"] = it }
            view.progress?.let { response.putAll(terminalFailureFields(it, progressOptions)) }
        }
        response.putAll(streamResponseFields(view.stdout, outputLimit, "stdout"))
        response.putAll(streamResponseFields(view.stderr, outputLimit, "stderr"))

        return response.filterValues { it != null }
    }
}
