package com.example.gradle.mcp.support

import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.Task
import java.io.File
import java.lang.reflect.Proxy
import java.util.AbstractSet
import java.util.concurrent.atomic.AtomicInteger

internal fun gradleProjectProxy(
    name: String = "root",
    path: String = ":",
    directory: File = File("/root"),
    tasks: List<Task> = emptyList(),
    children: List<GradleProject> = emptyList(),
): GradleProject =
    Proxy.newProxyInstance(
        GradleProject::class.java.classLoader,
        arrayOf(GradleProject::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> name
            "getPath" -> path
            "getProjectDirectory" -> directory
            "getDescription" -> null
            "getBuildDirectory" -> null
            "getParent" -> null
            "getChildren" -> toolingDomainObjectSet(children)
            "getTasks" -> toolingDomainObjectSet(tasks)
            "getProjectIdentifier" -> null
            else -> defaultProxyReturn(method)
        }
    } as GradleProject

internal fun gradleProjectConnectionProxy(
    project: GradleProject,
    getModelCalls: AtomicInteger? = null,
): ProjectConnection =
    Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getModel" -> {
                getModelCalls?.incrementAndGet()
                val modelType = args?.get(0) as Class<*>
                if (modelType == GradleProject::class.java) project else null
            }
            else -> defaultProxyReturn(method)
        }
    } as ProjectConnection

@Suppress("UNCHECKED_CAST")
internal fun <T> toolingDomainObjectSet(items: List<T>): DomainObjectSet<T> =
    object : AbstractSet<T>(), DomainObjectSet<T> {
        override fun iterator(): MutableIterator<T> = items.toMutableList().iterator()

        override val size: Int get() = items.size

        override fun getAll(): List<T> = items

        override fun getAt(index: Int): T = items[index]
    }
