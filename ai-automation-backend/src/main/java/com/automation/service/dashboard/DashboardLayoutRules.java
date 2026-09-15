package com.automation.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Limits of the dashboard layout format (spec E1) and the checks that DashboardLayoutValidator and
 * DashboardLayoutCanonicalizer share. Mirrors LAYOUT_LIMITS in the frontend's dashboardLayout.ts.
 */
final class DashboardLayoutRules {

    static final Set<Integer> SUPPORTED_FORMAT_VERSIONS = Set.of(2);
    static final int MAX_VIEWS = 20;
    static final int MAX_WIDGETS_PER_VIEW = 100;
    static final int MAX_CONFIG_KEYS = 20;
    static final int MAX_CONFIG_STRING = 500;
    static final int MAX_ID_LENGTH = 80;
    static final int MAX_NAME_LENGTH = 80;
    static final int MAX_DAYS = 365;

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private DashboardLayoutRules() {
    }

    static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw invalid(path + " must be an object");
        }
    }

    static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw invalid(path + " has an unknown field: " + echo(field));
            }
        });
    }

    static String requiredId(JsonNode node, String path) {
        if (node == null || !node.isTextual() || node.textValue().length() > MAX_ID_LENGTH
                || !ID.matcher(node.textValue()).matches()) {
            throw invalid(path + " must match " + ID.pattern() + " with at most " + MAX_ID_LENGTH + " characters");
        }
        return node.textValue();
    }

    /** A client-supplied name, safe to put into a log line: control characters replaced, capped at 40 chars. */
    static String echo(String raw) {
        String truncated = raw.length() > 40 ? raw.substring(0, 40) + "…" : raw;
        StringBuilder sanitized = new StringBuilder(truncated.length());
        truncated.chars().forEach(c -> sanitized.append(Character.isISOControl(c) ? '?' : (char) c));
        return sanitized.toString();
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid dashboard layout: " + message);
    }
}
