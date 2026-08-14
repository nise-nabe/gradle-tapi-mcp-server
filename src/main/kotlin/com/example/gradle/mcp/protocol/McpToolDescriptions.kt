package com.example.gradle.mcp.protocol

internal object McpToolDescriptions {
    const val CONNECT =
        "Connect via Tooling API. Keeps other connections; rejects when build active for same project " +
            "(includes activeBuildId)."

    const val CONNECTION_STATUS =
        "Connection status for one or all projects. refresh=true fetches BuildEnvironment per project."

    const val DISCONNECT =
        "Close one or all Tooling API connections. Running builds for disconnected projects are cancelled."

    const val BUILD_ENVIRONMENT =
        "Resolved Gradle/Java (BuildEnvironment). No projectPath; versionInfo needs Gradle 9.4+."

    const val JAVA_RUNTIMES =
        "Daemon Java + local JDKs from javaToolchains (default). No projectPath."

    const val BUILD_CACHE_STATUS =
        "Build/configuration cache settings. No projectPath. probeConfigurationCache=true for probe."

    const val PROJECT_OVERVIEW =
        "Project hierarchy and task counts; projectPath scopes connected-build subtree."

    const val GRADLE_BUILD =
        "GradleBuild: projects, included/editable builds. No projectPath."

    const val PROJECT_MODEL =
        "GradleProject model; projectPath scopes subtree. includeTasks=true for tasks."

    const val BUILD_INVOCATIONS =
        "Runnable tasks; projectPath scopes subtree. includeTaskSelectors=true for selectors."

    const val PROJECT_PUBLICATIONS =
        "Publications declared by the build. No projectPath."

    const val HELP =
        "Gradle CLI help (--help). No projectPath. Requires Gradle 9.4+."

    const val LIST_BUILDS =
        "Recent MCP builds from memory and .gradle/mcp-builds/. No Tooling API required."

    const val CANCEL_BUILD =
        "Cancel a build by buildId. Finished builds return not_running. Poll gradle_get_build_status."

    const val BUILD_STATUS =
        "Poll buildId (memory/disk). No includeOutput while running; use deltas or terminal failures. " +
            "Failed GRADLE_TASK includes capped problems; includeProblems for liveProblems. " +
            "Short polls; waitUntilComplete capped."

    const val RUN_TASKS =
        "Run Gradle tasks. background→buildId (queues if busy); foreground auto-detaches ~45s; busy→activeBuildId."

    const val RUN_TESTS =
        "Run selected JVM tests (classes/methods/patterns). Whole suite→gradle_run_tasks. " +
            "Multi-project infers taskPath or lists suggestedTaskPaths. " +
            "background/detach/queue like gradle_run_tasks."
}
