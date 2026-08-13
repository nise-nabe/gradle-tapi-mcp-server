package com.example.gradle.mcp.build

internal object TestLauncherSupport {
    const val RERUN_ARGUMENT = "--rerun"

    private const val TASK_NOT_FOUND_PREFIX = "Requested test task with path '"
    private const val TASK_NOT_SUPPORTED_MARKER =
        "not supported for executing tests via TestLauncher API."

    fun scopedTaskPaths(request: BuildRunRequest): List<String> {
        if (request.tasks.isNotEmpty()) {
            return request.tasks
        }
        val taskPath = request.selection.taskPathOrNull()
        return if (!taskPath.isNullOrBlank()) listOf(taskPath) else emptyList()
    }

    fun isTaskLookupFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains(TASK_NOT_FOUND_PREFIX) || message.contains(TASK_NOT_SUPPORTED_MARKER)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun shouldFallbackToBuildLauncher(
        request: BuildRunRequest,
        exception: Throwable,
        completedTaskCount: Int,
        failedTasks: List<String>,
    ): Boolean =
        scopedTaskPaths(request).isNotEmpty() &&
            completedTaskCount == 0 &&
            failedTasks.isEmpty() &&
            isTaskLookupFailure(exception)

    fun testFilterCliArguments(selection: TestRunSelection?): List<String> =
        when (selection) {
            is TestRunSelection.Classes ->
                selection.classes.flatMap { className -> listOf("--tests", className) }
            is TestRunSelection.Methods ->
                selection.methods.flatMap { (className, methods) ->
                    methods.flatMap { method -> listOf("--tests", "$className.$method") }
                }
            is TestRunSelection.Patterns ->
                selection.patterns.flatMap { pattern -> listOf("--tests", pattern) }
            null -> emptyList()
        }
}
