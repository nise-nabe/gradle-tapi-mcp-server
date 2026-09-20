package com.example.gradle.mcp.resolution;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
