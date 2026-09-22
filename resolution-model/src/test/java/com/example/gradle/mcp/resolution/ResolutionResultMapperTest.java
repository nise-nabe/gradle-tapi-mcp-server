package com.example.gradle.mcp.resolution;

import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolutionResultMapperTest {
    @Test
    void normalizeFilterTrimsAndTreatsBlankAsNull() {
        assertNull(ResolutionResultMapper.normalizeFilter(null));
        assertNull(ResolutionResultMapper.normalizeFilter("  "));
        assertTrue("guava".equals(ResolutionResultMapper.normalizeFilter(" guava ")));
    }

    @Test
    void matchesFilterAgainstRequestedSelectedOrFrom() {
        McpResolvedDependencyEdge edge = new DefaultMcpResolvedDependencyEdge(
                new DefaultMcpResolvedComponentIdentity("project :app", null, ":app", null),
                "com.google.guava:guava:31.0",
                new DefaultMcpResolvedComponentIdentity(
                        "com.google.guava:guava:31.1-jre",
                        "com.google.guava",
                        "guava",
                        "31.1-jre"
                ),
                true,
                null,
                new DefaultMcpSelectionReason(List.of("between versions"), true, false, false, false, false, false)
        );

        assertTrue(ResolutionResultMapper.matchesFilter(edge, null));
        assertTrue(ResolutionResultMapper.matchesFilter(edge, "guava"));
        assertTrue(ResolutionResultMapper.matchesFilter(edge, "31.1"));
        assertTrue(ResolutionResultMapper.matchesFilter(edge, ":app"));
        assertFalse(ResolutionResultMapper.matchesFilter(edge, "jackson"));
    }

    @Test
    void componentKeyDistinguishesComponentsSharingDisplayName() {
        McpResolvedComponentIdentity module = new DefaultMcpResolvedComponentIdentity(
                "lib", "com.acme", "lib", "1.0"
        );
        McpResolvedComponentIdentity project = new DefaultMcpResolvedComponentIdentity(
                "lib", null, ":lib", null
        );
        McpResolvedComponentIdentity other = new DefaultMcpResolvedComponentIdentity(
                "lib", null, null, null
        );

        assertNotEquals(
                ResolutionResultMapper.componentKey(module),
                ResolutionResultMapper.componentKey(project)
        );
        assertNotEquals(
                ResolutionResultMapper.componentKey(module),
                ResolutionResultMapper.componentKey(other)
        );
        assertNotEquals(
                ResolutionResultMapper.componentKey(project),
                ResolutionResultMapper.componentKey(other)
        );
    }

    @Test
    void componentKeyDistinguishesModuleVersions() {
        McpResolvedComponentIdentity v1 = new DefaultMcpResolvedComponentIdentity(
                "com.acme:lib:1.0", "com.acme", "lib", "1.0"
        );
        McpResolvedComponentIdentity v2 = new DefaultMcpResolvedComponentIdentity(
                "com.acme:lib:2.0", "com.acme", "lib", "2.0"
        );

        assertNotEquals(ResolutionResultMapper.componentKey(v1), ResolutionResultMapper.componentKey(v2));
    }

    @Test
    void mapCapsDependenciesAndComponentsWhileCountingAll() {
        ResolvedComponentResult root = component(moduleId("g:root:1", "g", "root", "1"));
        ResolvedComponentResult a = component(moduleId("g:a:1", "g", "a", "1"));
        ResolvedComponentResult b = component(moduleId("g:b:1", "g", "b", "1"));
        ResolvedComponentResult c = component(moduleId("g:c:1", "g", "c", "1"));
        Set<DependencyResult> dependencies = new LinkedHashSet<>(List.of(
                edge(root, selector("g", "a", "1"), a),
                edge(root, selector("g", "b", "1"), b),
                edge(a, selector("g", "c", "1"), c)
        ));
        Set<ResolvedComponentResult> components = new LinkedHashSet<>(List.of(root, a, b, c));

        McpDependencyResolution result = ResolutionResultMapper.map(
                ":app", "runtimeClasspath", null,
                resolution(root, dependencies, components), 2, 1
        );

        assertEquals(2, result.getDependencies().size());
        assertTrue(result.isDependenciesTruncated());
        assertEquals(3, result.getTotalDependencyCount());
        assertEquals(1, result.getComponents().size());
        assertTrue(result.isComponentsTruncated());
        assertEquals(4, result.getTotalComponentCount());
    }

    @Test
    void mapFilterCountsMatchesAndScopesComponentsToMatchedEdges() {
        ResolvedComponentResult root = component(moduleId("g:root:1", "g", "root", "1"));
        ResolvedComponentResult guava = component(moduleId(
                "com.google.guava:guava:31.1-jre", "com.google.guava", "guava", "31.1-jre"));
        ResolvedComponentResult unrelated = component(moduleId("o:x:1", "o", "x", "1"));
        ResolvedComponentResult unused = component(moduleId("o:y:1", "o", "y", "1"));
        Set<DependencyResult> dependencies = new LinkedHashSet<>(List.of(
                edge(root, selector("com.google.guava", "guava", "31.0"), guava),
                edge(root, selector("o", "x", "1"), unrelated)
        ));
        Set<ResolvedComponentResult> components =
                new LinkedHashSet<>(List.of(root, guava, unrelated, unused));

        McpDependencyResolution result = ResolutionResultMapper.map(
                ":app", "runtimeClasspath", "guava",
                resolution(root, dependencies, components), 500, 500
        );

        assertEquals(1, result.getDependencies().size());
        assertFalse(result.isDependenciesTruncated());
        assertEquals(1, result.getTotalDependencyCount());
        assertEquals(
                Set.of("g:root:1", "com.google.guava:guava:31.1-jre"),
                result.getComponents().stream()
                        .map(component -> component.getId().getDisplayName())
                        .collect(java.util.stream.Collectors.toSet())
        );
        assertEquals(2, result.getTotalComponentCount());
    }

    @Test
    void mapCountsUnresolvedEdgesTowardTotals() {
        ResolvedComponentResult root = component(moduleId("g:root:1", "g", "root", "1"));
        ResolvedComponentResult a = component(moduleId("g:a:1", "g", "a", "1"));
        DependencyResult missing = stub(UnresolvedDependencyResult.class, Map.of(
                "getFrom", root,
                "getRequested", selector("o", "missing", "1")
        ));
        Set<DependencyResult> dependencies = new LinkedHashSet<>(List.of(
                edge(root, selector("g", "a", "1"), a),
                missing
        ));
        Set<ResolvedComponentResult> components = new LinkedHashSet<>(List.of(root, a));

        McpDependencyResolution result = ResolutionResultMapper.map(
                ":app", "runtimeClasspath", null,
                resolution(root, dependencies, components), 500, 500
        );

        assertEquals(2, result.getDependencies().size());
        assertEquals(2, result.getTotalDependencyCount());
        assertFalse(result.getDependencies().get(1).isResolved());
    }

    private static ResolutionResult resolution(
            ResolvedComponentResult root,
            Set<DependencyResult> dependencies,
            Set<ResolvedComponentResult> components
    ) {
        return stub(ResolutionResult.class, Map.of(
                "getRoot", root,
                "getAllDependencies", dependencies,
                "getAllComponents", components
        ));
    }

    private static ResolvedComponentResult component(ComponentIdentifier id) {
        return stub(ResolvedComponentResult.class, Map.of("getId", id));
    }

    private static ResolvedDependencyResult edge(
            ResolvedComponentResult from,
            ComponentSelector requested,
            ResolvedComponentResult selected
    ) {
        return stub(ResolvedDependencyResult.class, Map.of(
                "getFrom", from,
                "getRequested", requested,
                "getSelected", selected
        ));
    }

    private static ModuleComponentSelector selector(String group, String module, String version) {
        return stub(ModuleComponentSelector.class, Map.of(
                "getGroup", group,
                "getModule", module,
                "getVersion", version
        ));
    }

    private static ModuleComponentIdentifier moduleId(
            String displayName, String group, String module, String version
    ) {
        return stub(ModuleComponentIdentifier.class, Map.of(
                "getDisplayName", displayName,
                "getGroup", group,
                "getModule", module,
                "getVersion", version
        ));
    }

    /**
     * Minimal Gradle API stub: methods named in [answers] return canned values,
     * object methods keep identity semantics, and everything else returns a
     * benign default (false / empty collection / null).
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, ?> answers) {
        return (T) Proxy.newProxyInstance(
                ResolutionResultMapperTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "stub:" + type.getSimpleName();
                        default:
                            break;
                    }
                    if (answers.containsKey(method.getName())) {
                        return answers.get(method.getName());
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (Set.class.isAssignableFrom(returnType)) {
                        return Set.of();
                    }
                    if (List.class.isAssignableFrom(returnType)) {
                        return List.of();
                    }
                    return null;
                }
        );
    }
}
