package com.example.gradle.mcp.model

import org.gradle.tooling.ProjectConnection

internal fun <T> ProjectConnection.fetchModel(modelType: Class<T>, prepareTasks: List<String>): T =
    if (prepareTasks.isEmpty()) {
        getModel(modelType)
    } else {
        model(modelType).forTasks(*prepareTasks.toTypedArray()).get()
    }
