package com.example.gradle.mcp.dependency

import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File

data class DependencyArtifactRef(
    val group: String,
    val name: String,
    val version: String,
) {
    fun gav(): String = "$group:$name:$version"

    fun validate() {
        require(group.isNotBlank() && name.isNotBlank() && version.isNotBlank()) {
            "artifact group/name/version must not be blank"
        }
        require(!group.contains('/') && !name.contains('/') && !version.contains('/')) {
            "artifact coordinates must not contain path separators"
        }
    }
}

data class SourcePathRef(
    val path: File,
    val group: String? = null,
    val name: String? = null,
    val version: String? = null,
) {
    fun gav(): String {
        val g = group?.takeIf { it.isNotBlank() } ?: "local"
        val n = name?.takeIf { it.isNotBlank() } ?: path.name
        val v = version?.takeIf { it.isNotBlank() } ?: "0"
        return "$g:$n:$v"
    }
}

data class ResolvedKeepSet(
    val mode: String,
    val members: List<KeepSetMember>,
)

object DependencyKeepSetResolver {
    fun resolve(
        connection: ProjectConnection?,
        artifacts: List<DependencyArtifactRef>,
        sourcePaths: List<SourcePathRef>,
        gradleUserHome: File? = null,
    ): ResolvedKeepSet {
        val explicit = artifacts.isNotEmpty() || sourcePaths.isNotEmpty()
        if (!explicit) {
            requireNotNull(connection) {
                "project connection is required when artifacts/sourcePaths are omitted"
            }
            val members = resolveFromIdea(connection)
            if (members.isEmpty()) {
                throw IllegalArgumentException(
                    "No dependency sources found via IdeaProject. " +
                        "Download sources, or pass sourcePaths / artifacts.",
                )
            }
            return ResolvedKeepSet(mode = "idea", members = members)
        }

        val members = ArrayList<KeepSetMember>()
        val missing = ArrayList<String>()
        for (artifact in artifacts) {
            artifact.validate()
            val jar = LocalSourcesJarLocator.find(artifact, gradleUserHome)
            if (jar == null) {
                missing.add(artifact.gav())
            } else {
                members.add(KeepSetMember(gav = artifact.gav(), sourceRoot = jar))
            }
        }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "Could not find sources jars in local Maven/Gradle caches for: " +
                    missing.joinToString(", ") +
                    ". Pass sourcePaths for local trees, or download sources first.",
            )
        }
        for (sourcePath in sourcePaths) {
            if (!sourcePath.path.exists()) {
                throw IllegalArgumentException("sourcePaths entry does not exist: ${sourcePath.path}")
            }
            members.add(KeepSetMember(gav = sourcePath.gav(), sourceRoot = sourcePath.path))
        }
        if (members.isEmpty()) {
            throw IllegalArgumentException(
                "No sources to index. Provide artifacts and/or sourcePaths, " +
                    "or omit both to use IdeaProject dependency sources.",
            )
        }
        return ResolvedKeepSet(mode = "explicit", members = members)
    }

    fun resolveFromIdea(connection: ProjectConnection): List<KeepSetMember> {
        val idea = connection.getModel(IdeaProject::class.java)
        val members = LinkedHashMap<String, KeepSetMember>()
        for (module in idea.modules) {
            for (dependency in module.dependencies) {
                val library = dependency as? IdeaSingleEntryLibraryDependency ?: continue
                val source = library.source ?: continue
                if (!source.isFile && !source.isDirectory) continue
                val moduleVersion = library.gradleModuleVersion
                val gav = if (moduleVersion != null) {
                    "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}"
                } else {
                    "unknown:${source.name}:0"
                }
                members.putIfAbsent(
                    "$gav|${source.absolutePath}",
                    KeepSetMember(gav = gav, sourceRoot = source),
                )
            }
        }
        return members.values.toList()
    }
}

object LocalSourcesJarLocator {
    fun find(artifact: DependencyArtifactRef, gradleUserHome: File? = null): File? {
        findInMavenLocal(artifact)?.let { return it }
        findInGradleCache(artifact, gradleUserHome)?.let { return it }
        return null
    }

    private fun findInMavenLocal(artifact: DependencyArtifactRef): File? {
        val base = File(
            System.getenv("M2_REPO")
                ?: File(System.getProperty("user.home"), ".m2/repository").path,
        )
        val jar = File(
            base,
            artifact.group.replace('.', '/') + "/" +
                artifact.name + "/" + artifact.version + "/" +
                "${artifact.name}-${artifact.version}-sources.jar",
        )
        return jar.takeIf { it.isFile }
    }

    private fun findInGradleCache(artifact: DependencyArtifactRef, gradleUserHome: File?): File? {
        val userHome = gradleUserHome
            ?: System.getenv("GRADLE_USER_HOME")?.let(::File)
            ?: File(System.getProperty("user.home"), ".gradle")
        val moduleDir = File(
            userHome,
            "caches/modules-2/files-2.1/${artifact.group}/${artifact.name}/${artifact.version}",
        )
        if (!moduleDir.isDirectory) return null
        return moduleDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "${artifact.name}-${artifact.version}-sources.jar" }
    }
}