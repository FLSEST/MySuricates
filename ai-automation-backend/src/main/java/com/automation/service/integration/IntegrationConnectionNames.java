package com.automation.service.integration;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Names of the connections a provider may have (0b3cbcc3). Every provider has at most one
 * connection per name; the connection that existed before names were introduced is called
 * {@link #DEFAULT}, and every caller that names none gets that one.
 */
public final class IntegrationConnectionNames {

    public static final String DEFAULT = "default";

    /** Lower-case letters, digits and dashes, starting with a letter or digit, at most 40 characters. */
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    /** {@link #DEFAULT} first, the others alphabetically. */
    public static final Comparator<String> ORDER =
            Comparator.comparing((String name) -> !DEFAULT.equals(name)).thenComparing(Comparator.naturalOrder());

    private IntegrationConnectionNames() {
    }

    /**
     * The name to use for {@code raw}: {@link #DEFAULT} when none is given (null or blank),
     * otherwise the trimmed name, which must match {@code [a-z0-9][a-z0-9-]{0,39}}.
     *
     * @throws IllegalArgumentException for a name outside that pattern
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        String name = raw.trim();
        if (!VALID.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid connection name '" + abbreviate(name)
                    + "' — use 1–40 lower-case letters, digits or dashes, starting with a letter or digit");
        }
        return name;
    }

    public static boolean isDefault(String name) {
        return DEFAULT.equals(name);
    }

    /** Label for logs and messages: {@code github} for the default connection, {@code github/work} otherwise. */
    public static String label(String providerId, String name) {
        return name == null || isDefault(name) ? providerId : providerId + "/" + name;
    }

    private static String abbreviate(String name) {
        return name.length() > 50 ? name.substring(0, 50) + "…" : name;
    }
}
