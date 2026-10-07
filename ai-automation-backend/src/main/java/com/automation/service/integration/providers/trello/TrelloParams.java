package com.automation.service.integration.providers.trello;

import com.automation.service.integration.providers.IntegrationParams;

import java.util.Map;

/** Parameter parsing shared by the Trello actions — pure functions, no I/O. */
final class TrelloParams {

    private TrelloParams() {}

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
