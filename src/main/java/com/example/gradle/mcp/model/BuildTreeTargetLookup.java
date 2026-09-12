package com.example.gradle.mcp.model;

import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.gradle.BasicGradleProject;
import org.gradle.tooling.model.gradle.GradleBuild;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves a Tooling API {@code buildTreePath} to a {@link BasicGradleProject} in a
 * {@link GradleBuild} tree, including included builds and {@code buildSrc} (editable builds).
 */
public final class BuildTreeTargetLookup {
    private BuildTreeTargetLookup() {
    }

    public static BasicGradleProject find(GradleBuild build, String buildTreePath) {
        if (build == null || buildTreePath == null || buildTreePath.isBlank()) {
            return null;
        }
        return find(build, buildTreePath, new HashSet<>());
    }

    private static BasicGradleProject find(GradleBuild build, String buildTreePath, Set<String> visited) {
        if (build == null || !visited.add(visitKey(build))) {
            return null;
        }
        BasicGradleProject match = findInProjects(build, buildTreePath);
        if (match != null) {
            return match;
        }
        BasicGradleProject included = findInBuilds(safeIncludedBuilds(build), buildTreePath, visited);
        if (included != null) {
            return included;
        }
        return findInBuilds(safeEditableBuilds(build), buildTreePath, visited);
    }

    private static BasicGradleProject findInProjects(GradleBuild build, String buildTreePath) {
        DomainObjectSet<? extends BasicGradleProject> projects = safeProjects(build);
        if (projects == null) {
            return null;
        }
        for (BasicGradleProject project : projects) {
            if (buildTreePath.equals(safeBuildTreePath(project))) {
                return project;
            }
        }
        return null;
    }

    private static BasicGradleProject findInBuilds(
            DomainObjectSet<? extends GradleBuild> builds,
            String buildTreePath,
            Set<String> visited
    ) {
        if (builds == null) {
            return null;
        }
        for (GradleBuild nested : builds) {
            BasicGradleProject found = find(nested, buildTreePath, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String visitKey(GradleBuild build) {
        try {
            File rootDir = build.getBuildIdentifier().getRootDir();
            if (rootDir != null) {
                return rootDir.getAbsolutePath();
            }
        } catch (RuntimeException ignored) {
            // UnsupportedMethodException or missing identifier; fall through.
        }
        return "identity:" + System.identityHashCode(build);
    }

    private static String safeBuildTreePath(BasicGradleProject project) {
        try {
            String path = project.getBuildTreePath();
            return path == null || path.isBlank() ? null : path;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static DomainObjectSet<? extends BasicGradleProject> safeProjects(GradleBuild build) {
        try {
            return build.getProjects();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static DomainObjectSet<? extends GradleBuild> safeIncludedBuilds(GradleBuild build) {
        try {
            return build.getIncludedBuilds();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static DomainObjectSet<? extends GradleBuild> safeEditableBuilds(GradleBuild build) {
        try {
            return build.getEditableBuilds();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
