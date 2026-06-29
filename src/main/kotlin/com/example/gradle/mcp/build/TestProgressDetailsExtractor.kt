package com.example.gradle.mcp.build

import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestProgressEvent
import org.gradle.tooling.events.test.source.ClassSource
import org.gradle.tooling.events.test.source.ClasspathResourceSource
import org.gradle.tooling.events.test.source.DirectorySource
import org.gradle.tooling.events.test.source.FileSource
import org.gradle.tooling.events.test.source.FilesystemSource
import org.gradle.tooling.events.test.source.MethodSource
import org.gradle.tooling.events.test.source.NoSource
import org.gradle.tooling.events.test.source.OtherSource

internal object TestProgressDetailsExtractor {
    fun fromGradleEvent(
        event: TestProgressEvent,
        failureMessage: String? = null,
    ): TestProgressDetailsSnapshot? {
        val descriptor = event.descriptor as? JvmTestOperationDescriptor
        val source = descriptor?.source
        var className = descriptor?.className
        var methodName = descriptor?.methodName
        val sourceType = when (source) {
            null -> null
            is MethodSource -> {
                className = className ?: source.className
                methodName = methodName ?: source.methodName
                "method"
            }
            is ClassSource -> {
                className = className ?: source.className
                "class"
            }
            is FileSource -> "file"
            is DirectorySource -> "directory"
            is ClasspathResourceSource -> "classpath_resource"
            is FilesystemSource -> "filesystem"
            is OtherSource -> "other"
            is NoSource -> "none"
            else -> source.javaClass.simpleName.ifBlank { source.javaClass.name }
        }
        val sourcePath = when (source) {
            is FileSource -> source.file.path
            is DirectorySource -> source.file.path
            is FilesystemSource -> source.file.path
            is ClasspathResourceSource -> source.classpathResourceName
            else -> null
        }
        val sourcePosition = when (source) {
            is FileSource -> source.position
            is ClasspathResourceSource -> source.position
            else -> null
        }
        return TestProgressDetailsSnapshot(
            className = className,
            methodName = methodName,
            sourceType = sourceType,
            sourcePath = sourcePath,
            sourceLine = sourcePosition?.line,
            sourceColumn = sourcePosition?.column,
            failureMessage = failureMessage,
        ).takeUnlessBlank()
    }

    fun fromDiskMap(
        eventType: String,
        map: Map<String, Any?>,
    ): TestProgressDetailsSnapshot? {
        if (!eventType.startsWith("TEST_")) {
            return null
        }
        val failureMessage = (map["failureMessage"] as? String) ?: (map["outcome"] as? String)
        return TestProgressDetailsSnapshot(
            className = map["className"] as? String,
            methodName = map["methodName"] as? String,
            sourceType = map["sourceType"] as? String,
            sourcePath = map["sourcePath"] as? String,
            sourceLine = (map["sourceLine"] as? Number)?.toInt(),
            sourceColumn = (map["sourceColumn"] as? Number)?.toInt(),
            failureMessage = failureMessage,
        ).takeUnlessBlank()
    }
}

internal fun TestProgressDetailsSnapshot.takeUnlessBlank(): TestProgressDetailsSnapshot? =
    if (
        className == null &&
        methodName == null &&
        sourceType == null &&
        sourcePath == null &&
        sourceLine == null &&
        sourceColumn == null &&
        failureMessage == null
    ) {
        null
    } else {
        this
    }
