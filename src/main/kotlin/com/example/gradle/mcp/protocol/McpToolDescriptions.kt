package com.example.gradle.mcp.protocol

internal object McpToolDescriptions {
    const val CONNECT =
        "Connect to a Gradle project via Tooling API. Keeps other connections; rejects if a build is running for the same project."

    const val CONNECTION_STATUS =
        "Connection status for one or all projects. refresh=true fetches missing BuildEnvironment (per project when listing all)."

    const val DISCONNECT =
        "Close one or all Tooling API connections. Running builds for disconnected projects are cancelled."

    const val BUILD_ENVIRONMENT =
        "Resolved Gradle/Java environment (BuildEnvironment). Lightweight stack check; versionInfo needs Gradle 9.4+."

    const val JAVA_RUNTIMES =
        "Daemon Java plus local JDKs from javaToolchains when includeToolchains=true (default). Reconnect to refresh daemon Java."

    const val BUILD_CACHE_STATUS =
        "Build and configuration cache settings without a full build. Set probeConfigurationCache=true for a compatibility probe."

    const val PROJECT_OVERVIEW =
        "Project hierarchy and task counts without task lists. Token-efficient default for project context."

    const val GRADLE_BUILD =
        "GradleBuild structure: projects, included builds, editable builds. Prefer for composite/includeBuild repos."

    const val PROJECT_MODEL =
        "GradleProject model. includeTasks=true for tasks. maxTasks is a global tree cap (root first); " +
            "root adds tasksTruncated when capped."

    const val BUILD_INVOCATIONS =
        "Runnable Gradle tasks; global maxTasks cap adds tasksTruncated. Task selectors omitted unless includeTaskSelectors=true."

    const val PROJECT_PUBLICATIONS =
        "Publications declared by the build."

    const val HELP =
        "Gradle CLI help text (gradle --help). Requires Gradle 9.4+."

    const val LIST_BUILDS =
        "Recent MCP builds from memory and .gradle/mcp-builds/ on disk. No Tooling API connection required."

    const val CANCEL_BUILD =
        "Cancel a build by buildId. Finished builds return not_running. Poll gradle_get_build_status."

    const val BUILD_STATUS =
        "Poll build by buildId (memory/disk; no Tooling API). " +
            "waitUntilComplete is capped—prefer short polls. Optional output/progress."

    const val RUN_TASKS =
        "Run Gradle tasks. background returns buildId; queueIfBusy enqueues when busy."

    const val RUN_TESTS =
        "Run JVM tests by class, method, or pattern. taskPath/tasks scope Test suites; " +
            "queueIfBusy enqueues when busy."
}
