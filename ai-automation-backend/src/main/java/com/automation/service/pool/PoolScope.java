package com.automation.service.pool;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Category of a pool entry - the distinguishing feature by which a foreign reader recognises before
 * installing whether an entry is meant for their project.
 *
 * <ul>
 *   <li>{@link #REPO} - accesses the structure of this repository
 *       ({@code ai-automation-backend}, {@code mvnw}, {@code scripts/}). Leads nowhere elsewhere.</li>
 *   <li>{@link #PORTABLE} - parametrised via {@code project_path} (or own context variables)
 *       and thus runnable in every project.</li>
 *   <li>{@link #EXTERNAL} - needs a tool outside this repository and the chosen
 *       project; the prerequisite is in the description (TODO 7ddacd1d, e.g. the SaaS factory).</li>
 * </ul>
 *
 * <p>The value is pool metadata, not a field of the workflow definition: it is created on export
 * ({@code POST /api/v1/pool/export/{workflowId}?scope=repo|portable|external}) and shown in the pool browser.
 * Rules: {@code docs/workflow-pool-kuratierung.md}.
 */
public enum PoolScope {

    REPO,
    PORTABLE,
    EXTERNAL;

    /** The value as it appears in the pool JSON. */
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the query parameter. Without a value {@link #PORTABLE} applies: the default is the
     * weaker promise, a repo-bound entry must be marked as such deliberately.
     *
     * @throws IllegalArgumentException for an unknown value
     */
    public static PoolScope fromParam(String value) {
        if (value == null || value.isBlank()) {
            return PORTABLE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.jsonValue().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unbekannter scope: '" + value + "' — erlaubt sind " + allowedValues()));
    }

    /** The allowed values for error messages, e.g. {@code 'repo', 'portable', 'external'}. */
    public static String allowedValues() {
        return Arrays.stream(values()).map(s -> "'" + s.jsonValue() + "'").collect(Collectors.joining(", "));
    }
}
