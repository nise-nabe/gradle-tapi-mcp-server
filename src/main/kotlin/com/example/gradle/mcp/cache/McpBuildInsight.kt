package com.example.gradle.mcp.cache

import com.example.gradle.mcp.build.BuildExecutionManager
import com.example.gradle.mcp.build.BuildOutputParser
import com.example.gradle.mcp.connection.ProjectDirectoryResolver
import java.io.File

fun BuildExecutionManager.lastMcpBuildInsight(projectDirectory: File): LastMcpBuildInsight? {
    val snapshot = lastCompletedBuildSnapshot(projectDirectory) ?: return null
    val snapshotProject = snapshot.projectDirectory ?: return null
    if (ProjectDirectoryResolver.canonicalKey(File(snapshotProject)) !=
        ProjectDirectoryResolver.canonicalKey(projectDirectory)
    ) {
        return null
    }
    val summary = BuildOutputParser.parse(snapshot.stdout)
    return LastMcpBuildInsight(
        buildId = snapshot.buildId,
        kind = snapshot.kind.name.lowercase(),
        tasks = snapshot.tasks,
        testClasses = snapshot.testClasses,
        finishedAt = snapshot.finishedAt.toString(),
        outcome = snapshot.outcome,
        taskSummaryLine = summary.taskSummaryLine,
        resultLine = summary.resultLine,
        taskStats = TaskExecutionStatsParser.parse(summary.taskSummaryLine),
    )
}
