package com.example.gradle.mcp.protocol

internal object McpToolDescriptions {
    const val CONNECT =
        "Connect to a Gradle project via Tooling API. Keeps other connections; rejects if a build is running for the same project."

    const val CONNECTION_STATUS =
        "Tooling API connection status for one project or all connections. Omit projectDirectory for the full list."

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
        "GradleProject model. Tasks omitted by default; set includeTasks=true only when needed."

    const val BUILD_INVOCATIONS =
        "Runnable Gradle tasks. Task selectors omitted by default unless includeTaskSelectors=true."

    const val PROJECT_PUBLICATIONS =
        "Publications declared by the build."

    const val HELP =
        "Gradle CLI help text (gradle --help). Requires Gradle 9.4+."

    const val LIST_BUILDS =
        "Recent MCP builds from memory and .gradle/mcp-builds/ on disk. No Tooling API connection required."

    const val CANCEL_BUILD =
        "Cancel a background build by buildId. Poll gradle_get_build_status until no longer running."

    const val BUILD_STATUS =
        "Poll a background build by buildId. Outcome/summary by default; set includeOutput/includeProgress/includeProblems/includeDownloads/includeTestDetails as needed. See reference for disk vs memory behavior."

    const val RUN_TASKS =
        "Run Gradle task paths. Outcome/summary by default; background=true returns buildId for gradle_get_build_status. Only one MCP build per projectDirectory at a time (BUILD_ALREADY_RUNNING on overlap)."

    const val RUN_TESTS =
        "Run JVM tests by class, method, or pattern. Only one MCP build per projectDirectory at a time (BUILD_ALREADY_RUNNING on overlap). Provide one of testMethods, testClasses, or includePattern(s). See reference for selection rules."
}
