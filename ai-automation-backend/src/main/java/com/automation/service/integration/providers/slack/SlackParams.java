package com.automation.service.integration.providers.slack;

import com.automation.service.integration.providers.IntegrationParams;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parameter parsing shared by the Slack actions — pure functions, no I/O. */
final class SlackParams {

    /** Message timestamps look like {@code 1503435956.000247}; oldest/latest may omit the fraction. */
    private static final Pattern TS = Pattern.compile("\\d{1,12}(\\.\\d{1,9})?");
    private static final Set<String> CHANNEL_TYPES = Set.of("public_channel", "private_channel", "mpim", "im");

    private SlackParams() {}

    /** Extracts the bot token from the framework's "label|token" credential format. */
    static String extractToken(String combined) {
        if (combined == null) return null;
        int idx = combined.indexOf('|');
        return idx >= 0 ? combined.substring(idx + 1) : combined;
    }

    static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new IllegalArgumentException("Required parameter missing: " + key);
        }
        return val.toString().trim();
    }

    static String optionalString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val == null || val.toString().isBlank() ? null : val.toString().trim();
    }

    /** Integer in {@code [min, max]}, {@code defaultValue} when absent; read like its text ({@link IntegrationParams}). */
    static int boundedInt(Map<String, Object> params, String key, int min, int max, int defaultValue) {
        Integer n = IntegrationParams.optionalInt(params.get(key), key);
        if (n == null) return defaultValue;
        if (n < min || n > max) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be between " + min + " and " + max);
        }
        return n;
    }

    /** A Slack message timestamp ({@code thread_ts}, {@code oldest}, {@code latest}) or {@code null}. */
    static String timestamp(Map<String, Object> params, String key) {
        String ts = optionalString(params, key);
        if (ts != null && !TS.matcher(ts).matches()) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be a Slack timestamp like 1503435956.000247");
        }
        return ts;
    }

    /** Comma-separated conversation types, each one of public_channel, private_channel, mpim, im. */
    static String channelTypes(Map<String, Object> params) {
        String raw = optionalString(params, "types");
        if (raw == null) return "public_channel";
        StringBuilder out = new StringBuilder();
        for (String part : raw.split(",")) {
            String type = part.trim();
            if (type.isEmpty()) continue;
            if (!CHANNEL_TYPES.contains(type)) {
                throw new IllegalArgumentException("Parameter 'types' allows only public_channel, private_channel, mpim, im");
            }
            if (!out.isEmpty()) out.append(',');
            out.append(type);
        }
        return out.isEmpty() ? "public_channel" : out.toString();
    }

    /** "chat:write,channels:read" (x-oauth-scopes header) → "chat:write channels:read"; blank → null (unknown). */
    static String parseScopeHeader(String header) {
        if (header == null || header.isBlank()) return null;
        String joined = String.join(" ", header.trim().split("\\s*,\\s*")).trim();
        return joined.isEmpty() ? null : joined;
    }
}
