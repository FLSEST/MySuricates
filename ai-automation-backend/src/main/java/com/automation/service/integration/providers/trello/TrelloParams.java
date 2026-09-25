package com.automation.service.integration.providers.trello;

import com.automation.service.integration.providers.IntegrationParams;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Parameter parsing shared by the Trello actions — pure functions, no I/O. */
final class TrelloParams {

    private TrelloParams() {}

    static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /** Missing or blank → {@code defaultValue}; a number is read like its text ({@link IntegrationParams}). */
    static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        return IntegrationParams.intParam(params, key, defaultValue);
    }

    static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) throw new IllegalArgumentException("Required parameter missing: " + key);
        return val.toString();
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
