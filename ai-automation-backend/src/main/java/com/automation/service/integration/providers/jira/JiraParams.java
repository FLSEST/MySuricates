package com.automation.service.integration.providers.jira;

import com.automation.service.integration.providers.IntegrationParams;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Parameter parsing shared by the Jira actions — pure functions, no I/O. */
final class JiraParams {

    private JiraParams() {}

    static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /**
     * Missing or blank → {@code defaultValue}; a number is read like its text ({@link IntegrationParams}).
     * Until TODO ac639b39 a blank string threw a bare {@link NumberFormatException} here, unlike the
     * other providers.
     */
    static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        return IntegrationParams.intParam(params, key, defaultValue);
    }

    static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) throw IntegrationParams.missing(key);
        return val.toString();
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Wraps plain text as a minimal Atlassian Document Format (ADF) node — the format Jira's v3
     * API requires for rich-text fields such as issue descriptions and comments.
     */
    static Map<String, Object> buildAdfText(String text) {
        return Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of(
                                "type", "text",
                                "text", text
                        ))
                ))
        );
    }
}
