package com.example.gradle.mcp.resolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Filters, sorts, and caps configuration catalog entries. Package-visible for unit tests.
 */
final class ConfigurationCatalogMapper {
    static final int DEFAULT_MAX_CONFIGURATIONS = 200;
    static final int MAX_SUGGESTIONS = 20;

    private static final Comparator<McpConfigurationSummary> BY_RESOLVABLE_THEN_NAME =
            Comparator.comparing((McpConfigurationSummary summary) -> !summary.isCanBeResolved())
                    .thenComparing(McpConfigurationSummary::getName, String.CASE_INSENSITIVE_ORDER);

    private ConfigurationCatalogMapper() {
    }

    static List<McpConfigurationSummary> selectResolvableOrConsumable(List<McpConfigurationSummary> all) {
        List<McpConfigurationSummary> selected = new ArrayList<>();
        for (McpConfigurationSummary summary : all) {
            if (summary.isCanBeResolved() || summary.isCanBeConsumed()) {
                selected.add(summary);
            }
        }
        selected.sort(BY_RESOLVABLE_THEN_NAME);
        return selected;
    }

    static List<String> resolvableNames(List<McpConfigurationSummary> all) {
        List<String> names = new ArrayList<>();
        for (McpConfigurationSummary summary : all) {
            if (summary.isCanBeResolved()) {
                names.add(summary.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static String resolvableSuffix(List<String> resolvableNames) {
        if (resolvableNames.isEmpty()) {
            return "Resolvable: (none)";
        }
        boolean truncated = resolvableNames.size() > MAX_SUGGESTIONS;
        List<String> shown = truncated
                ? new ArrayList<>(resolvableNames.subList(0, MAX_SUGGESTIONS))
                : resolvableNames;
        StringBuilder suffix = new StringBuilder("Resolvable: ");
        suffix.append(String.join(", ", shown));
        if (truncated) {
            suffix.append(" (+").append(resolvableNames.size() - MAX_SUGGESTIONS).append(" more)");
        }
        return suffix.toString();
    }

    static CatalogSlice cap(List<McpConfigurationSummary> selected, int maxConfigurations) {
        int limit = maxConfigurations > 0 ? maxConfigurations : DEFAULT_MAX_CONFIGURATIONS;
        boolean truncated = selected.size() > limit;
        List<McpConfigurationSummary> items = truncated
                ? new ArrayList<>(selected.subList(0, limit))
                : selected;
        return new CatalogSlice(items, truncated, selected.size());
    }

    static final class CatalogSlice {
        final List<McpConfigurationSummary> configurations;
        final boolean truncated;
        final int totalCount;

        CatalogSlice(List<McpConfigurationSummary> configurations, boolean truncated, int totalCount) {
            this.configurations = configurations;
            this.truncated = truncated;
            this.totalCount = totalCount;
        }
    }
}
