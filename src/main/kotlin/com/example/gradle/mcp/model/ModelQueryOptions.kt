package com.example.gradle.mcp.model

import com.example.gradle.mcp.protocol.optionalBoolean
import com.example.gradle.mcp.protocol.optionalPositiveInt
import com.example.gradle.mcp.protocol.optionalString

data class ModelQueryOptions(
    val includeTasks: Boolean = false,
    val includeTaskDetails: Boolean = false,
    val includeTaskSelectors: Boolean = false,
    val taskGroup: String? = null,
    val taskNamePrefix: String? = null,
    val maxTasks: Int? = null,
) {
    companion object {
        fun fromArgs(args: Map<String, Any>): ModelQueryOptions =
            ModelQueryOptions(
                includeTasks = args.optionalBoolean("includeTasks", default = false),
                includeTaskDetails = args.optionalBoolean("includeTaskDetails", default = false),
                includeTaskSelectors = args.optionalBoolean("includeTaskSelectors", default = false),
                taskGroup = args.optionalString("taskGroup"),
                taskNamePrefix = args.optionalString("taskNamePrefix"),
                maxTasks = args.optionalPositiveInt("maxTasks"),
            )
    }
}
