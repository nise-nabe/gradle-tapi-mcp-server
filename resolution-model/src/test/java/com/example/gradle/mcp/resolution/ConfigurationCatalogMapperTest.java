package com.example.gradle.mcp.resolution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationCatalogMapperTest {
    @Test
    void selectResolvableOrConsumableOmitsDeclarableOnlyAndSortsResolvableFirst() {
        List<McpConfigurationSummary> selected = ConfigurationCatalogMapper.selectResolvableOrConsumable(
                List.of(
                        summary("implementation", false, false),
                        summary("apiElements", false, true),
                        summary("runtimeClasspath", true, false),
                        summary("compileClasspath", true, false)
                )
        );

        assertEquals(
                List.of("compileClasspath", "runtimeClasspath", "apiElements"),
                selected.stream().map(McpConfigurationSummary::getName).toList()
        );
    }

    @Test
    void capTruncatesAndReportsTotal() {
        List<McpConfigurationSummary> selected = List.of(
                summary("a", true, false),
                summary("b", true, false),
                summary("c", false, true)
        );

        ConfigurationCatalogMapper.CatalogSlice slice = ConfigurationCatalogMapper.cap(selected, 2);

        assertEquals(List.of("a", "b"), slice.configurations.stream().map(McpConfigurationSummary::getName).toList());
        assertTrue(slice.truncated);
        assertEquals(3, slice.totalCount);
    }

    @Test
    void capUsesDefaultWhenMaxIsZero() {
        List<McpConfigurationSummary> selected = List.of(summary("runtimeClasspath", true, false));

        ConfigurationCatalogMapper.CatalogSlice slice = ConfigurationCatalogMapper.cap(selected, 0);

        assertFalse(slice.truncated);
        assertEquals(1, slice.totalCount);
        assertEquals(1, slice.configurations.size());
    }

    @Test
    void resolvableSuffixTruncatesWithRemainderCount() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ConfigurationCatalogMapper.MAX_SUGGESTIONS + 3; i++) {
            names.add("cfg" + i);
        }

        String suffix = ConfigurationCatalogMapper.resolvableSuffix(names);

        assertTrue(suffix.startsWith("Resolvable: cfg0"));
        assertTrue(suffix.contains("(+3 more)"));
        assertFalse(suffix.contains("cfg20"));
    }

    @Test
    void resolvableSuffixForEmptyList() {
        assertEquals("Resolvable: (none)", ConfigurationCatalogMapper.resolvableSuffix(List.of()));
    }

    private static McpConfigurationSummary summary(String name, boolean resolved, boolean consumed) {
        return new DefaultMcpConfigurationSummary(name, resolved, consumed, null, Map.of(), List.of());
    }
}
