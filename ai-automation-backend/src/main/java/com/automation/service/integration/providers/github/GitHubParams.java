package com.automation.service.integration.providers.github;

import com.automation.service.integration.providers.IntegrationParams;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Parameter parsing shared by the GitHub actions — pure functions, no I/O. */
final class GitHubParams {

    private GitHubParams() {}

    /** Extracts the PAT from the framework's "username|token" credential format. */
    static String extractToken(String combined) {
        if (combined == null) return null;
        int idx = combined.indexOf('|');
        return idx >= 0 ? combined.substring(idx + 1) : combined;
    }

    static String[] splitRepo(String repo) {
        String[] parts = repo.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Parameter 'repo' must be in the form 'owner/repo'");
        }
        return parts;
    }

    static List<String> csv(Object value) {
        List<String> result = new ArrayList<>();
        for (String s : Arrays.asList(value.toString().split(","))) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    static String paramString(Map<String, Object> params, String key, String defaultValue) {
        return IntegrationParams.paramString(params, key, defaultValue);
    }

    /** Missing or blank → {@code defaultValue}; a number is read like its text ({@link IntegrationParams}). */
    static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        return IntegrationParams.intParam(params, key, defaultValue);
    }

    static String requireParam(Map<String, Object> params, String key) {
        return IntegrationParams.requireParam(params, key);
    }

    static String urlEncode(String value) {
        return IntegrationParams.urlEncode(value);
    }
}
