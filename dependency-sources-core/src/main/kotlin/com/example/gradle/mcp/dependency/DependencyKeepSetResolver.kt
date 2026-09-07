package com.example.gradle.mcp.dependency

import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File
import java.io.IOException

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
    val downloadedGavs: List<String> = emptyList(),
)

object DependencyKeepSetResolver {
    fun resolve(
        connection: ProjectConnection?,
        artifacts: List<DependencyArtifactRef>,
        sourcePaths: List<SourcePathRef>,
        gradleUserHome: File? = null,
        downloadSources: Boolean = false,
        sourcesJarCacheDir: File? = null,
        sourcesJarFetcher: SourcesJarFetcher = MavenCentralSourcesJarFetcher,
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
                        "Download sources, or pass sourcePaths / artifacts" +
                        (if (downloadSources) " (downloadSources applies only to artifacts[])." else "."),
                )
            }
            return ResolvedKeepSet(mode = "idea", members = members)
        }

        val members = ArrayList<KeepSetMember>()
        val missing = ArrayList<String>()
        val downloadFailed = ArrayList<String>()
        val downloaded = ArrayList<String>()
        for (artifact in artifacts) {
            artifact.validate()
            var jar = LocalSourcesJarLocator.find(artifact, gradleUserHome, sourcesJarCacheDir)
            if (jar == null && downloadSources) {
                val cacheDir = sourcesJarCacheDir
                    ?: throw IllegalArgumentException(
                        "downloadSources=true requires a sources jar cache directory",
                    )
                val destination = SourcesJarCacheLayout.jarFile(cacheDir, artifact)
                try {
                    jar = sourcesJarFetcher.fetch(artifact, destination)
                    if (jar != null) {
                        downloaded.add(artifact.gav())
                    } else {
                        downloadFailed.add(artifact.gav())
                    }
                } catch (error: IOException) {
                    downloadFailed.add("${artifact.gav()} (${error.message})")
                }
            }
            if (jar == null) {
                missing.add(artifact.gav())
            } else {
                members.add(KeepSetMember(gav = artifact.gav(), sourceRoot = jar))
            }
        }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                MissingSourcesMessage.build(
                    missingGavs = missing,
                    downloadFailedGavs = downloadFailed,
                    downloadSources = downloadSources,
                    gradleUserHome = gradleUserHome,
                    sourcesJarCacheDir = sourcesJarCacheDir,
                ),
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
        return ResolvedKeepSet(
            mode = "explicit",
            members = members,
            downloadedGavs = downloaded,
        )
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

object MissingSourcesMessage {
    fun build(
        missingGavs: List<String>,
        downloadFailedGavs: List<String> = emptyList(),
        downloadSources: Boolean,
        gradleUserHome: File?,
        sourcesJarCacheDir: File?,
    ): String {
        val searched = LocalSourcesJarLocator.searchedLocations(gradleUserHome, sourcesJarCacheDir)
        val builder = StringBuilder()
        builder.append("Could not find sources jars in local caches for: ")
        builder.append(missingGavs.joinToString(", "))
        builder.append('.')
        builder.append(" Searched: ").append(searched.joinToString("; ")).append('.')
        if (downloadSources) {
            if (downloadFailedGavs.isNotEmpty()) {
                builder.append(" Maven repository download did not succeed for: ")
                builder.append(downloadFailedGavs.joinToString(", "))
                builder.append('.')
            } else {
                builder.append(" downloadSources=true was set but jars were still unavailable.")
            }
            builder.append(" Pass sourcesRepositories for corporate mirrors, pass sourcePaths ")
            builder.append("for local trees, or place *-sources.jar under ")
            builder.append("Maven local / Gradle cache / MCP jars cache.")
        } else {
            builder.append(" Pass downloadSources=true to fetch into the ")
            builder.append("project MCP jars cache (.gradle/mcp-dependency-sources/jars/) ")
            builder.append("(optional sourcesRepositories for corporate Maven mirrors; ")
            builder.append("default Maven Central), pass sourcePaths for local trees, ")
            builder.append("or download sources first.")
        }
        return builder.toString()
    }
}

object LocalSourcesJarLocator {
    fun find(
        artifact: DependencyArtifactRef,
        gradleUserHome: File? = null,
        sourcesJarCacheDir: File? = null,
    ): File? {
        findInMavenLocal(artifact)?.let { return it }
        findInGradleCache(artifact, gradleUserHome)?.let { return it }
        findInMcpCache(artifact, sourcesJarCacheDir)?.let { return it }
        return null
    }

    fun searchedLocations(gradleUserHome: File?, sourcesJarCacheDir: File?): List<String> {
        val locations = ArrayList<String>(3)
        locations.add("Maven local (${mavenLocalBase().absolutePath})")
        locations.add("Gradle modules cache (${gradleModulesBase(gradleUserHome).absolutePath})")
        if (sourcesJarCacheDir != null) {
            locations.add("MCP jars cache (${sourcesJarCacheDir.absolutePath})")
        }
        return locations
    }

    private fun findInMavenLocal(artifact: DependencyArtifactRef): File? {
        val jar = File(
            mavenLocalBase(),
            artifact.group.replace('.', '/') + "/" +
                artifact.name + "/" + artifact.version + "/" +
                "${artifact.name}-${artifact.version}-sources.jar",
        )
        return jar.takeIf { it.isFile }
    }

    private fun findInGradleCache(artifact: DependencyArtifactRef, gradleUserHome: File?): File? {
        val moduleDir = File(
            gradleModulesBase(gradleUserHome),
            "${artifact.group}/${artifact.name}/${artifact.version}",
        )
        if (!moduleDir.isDirectory) return null
        return moduleDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "${artifact.name}-${artifact.version}-sources.jar" }
    }

    private fun findInMcpCache(artifact: DependencyArtifactRef, sourcesJarCacheDir: File?): File? {
        if (sourcesJarCacheDir == null) return null
        return SourcesJarCacheLayout.jarFile(sourcesJarCacheDir, artifact).takeIf { it.isFile }
    }

    private fun mavenLocalBase(): File =
        File(
            System.getenv("M2_REPO")
                ?: File(System.getProperty("user.home"), ".m2/repository").path,
        )

    private fun gradleModulesBase(gradleUserHome: File?): File {
        val userHome = gradleUserHome
            ?: System.getenv("GRADLE_USER_HOME")?.let(::File)
            ?: File(System.getProperty("user.home"), ".gradle")
        return File(userHome, "caches/modules-2/files-2.1")
    }
}
