package com.example.gradle.mcp.protocol

import com.example.gradle.mcp.build.BuildProgressSnapshot
import com.example.gradle.mcp.build.BuildProgressTracker
import com.example.gradle.mcp.build.DownloadProgressSnapshot
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProgressResponseOptionsTest {
    @Test
    fun `fromArgs defaults includeDownloads to false`() {
        val options = ProgressResponseOptions.fromArgs(emptyMap())
        options.includeDownloads.shouldBeFalse()
    }

    @Test
    fun `optionalDownloadFields exposes downloads without includeProgress`() {
        val snapshot = BuildProgressSnapshot(
            status = BuildProgressTracker.STATUS_RUNNING,
            currentOperation = "download",
            completedTaskCount = 0,
            runningTaskCount = 0,
            failedTaskCount = 0,
            completedTasks = emptyList(),
            runningTasks = emptyList(),
            failedTasks = emptyList(),
            recentEvents = emptyList(),
            totalEventCount = 0,
            recentDownloads = listOf(
                DownloadProgressSnapshot(
                    uri = "https://repo.example.com/foo.jar",
                    status = BuildProgressTracker.DOWNLOAD_STATUS_SUCCEEDED,
                    bytesDownloaded = 1024L,
                ),
            ),
            activeDownloadCount = 0,
        )
        val fields = optionalDownloadFields(ProgressResponseOptions(includeDownloads = true), snapshot)
        fields["activeDownloadCount"] shouldBe 0
        (fields["recentDownloads"] as List<*>).single().let { d ->
            (d as Map<*, *>)["uri"] shouldBe "https://repo.example.com/foo.jar"
        }
    }
}
