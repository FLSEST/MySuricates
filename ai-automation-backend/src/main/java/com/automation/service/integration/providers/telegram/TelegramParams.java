package com.automation.service.integration.providers.telegram;

import com.automation.service.integration.providers.IntegrationParams;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parameter parsing shared by the Telegram actions — pure functions, no I/O. A JSON number is read
 * like its text: {@code 1.5}, {@code 5.0} and values beyond {@code long} fail as the strings do,
 * instead of being cut off or wrapping around.
 */
final class TelegramParams {

    static final int MAX_TEXT = 4096;
    private static final Pattern NUMERIC_CHAT = Pattern.compile("-?\\d{1,20}");
    private static final Pattern CHANNEL_USERNAME = Pattern.compile("@[A-Za-z][A-Za-z0-9_]{3,31}");
    private static final Set<String> PARSE_MODES = Set.of("MarkdownV2", "HTML");

    private TelegramParams() {}

    /** Extracts the bot token from the framework's "label|token" credential format. */
    static String extractToken(String combined) {
        if (combined == null) return null;
        int idx = combined.indexOf('|');
        return (idx >= 0 ? combined.substring(idx + 1) : combined).trim();
    }

    /** Chat id as the Bot API takes it: a (possibly negative) integer or {@code @channelusername}. */
    static Object chatId(Map<String, Object> params) {
        Object val = params.get("chat_id");
        String raw = val == null ? "" : val.toString().trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Required parameter missing: chat_id");
        if (NUMERIC_CHAT.matcher(raw).matches()) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                // 19–20 digits pass the pattern but can exceed a long.
                throw invalidChatId();
            }
        }
        if (CHANNEL_USERNAME.matcher(raw).matches()) return raw;
        throw invalidChatId();
    }

    private static IllegalArgumentException invalidChatId() {
        return new IllegalArgumentException("Parameter 'chat_id' must be a numeric chat id or @channelusername");
    }

    /** Message text, 1–4096 characters as documented for sendMessage. */
    static String text(Map<String, Object> params) {
        Object val = params.get("text");
        if (val == null || val.toString().isBlank()) throw new IllegalArgumentException("Required parameter missing: text");
        String text = val.toString();
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("Parameter 'text' is longer than " + MAX_TEXT + " characters");
        }
        return text;
    }

    /** {@code MarkdownV2} or {@code HTML}; {@code null} = plain text. */
    static String parseMode(Map<String, Object> params) {
        Object val = params.get("parse_mode");
        if (val == null || val.toString().isBlank()) return null;
        String mode = val.toString().trim();
        if (!PARSE_MODES.contains(mode)) {
            throw new IllegalArgumentException("Parameter 'parse_mode' must be MarkdownV2 or HTML");
        }
        return mode;
    }

    static Boolean bool(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) return null;
        if (val instanceof Boolean b) return b;
        String s = val.toString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw new IllegalArgumentException("Parameter '" + key + "' must be true or false");
    }

    static Long optionalLong(Map<String, Object> params, String key) {
        return IntegrationParams.optionalLong(params.get(key), key);
    }

    /** getUpdates limit: 1–100, default 100. */
    static int updatesLimit(Map<String, Object> params) {
        Long limit = optionalLong(params, "limit");
        if (limit == null) return 100;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Parameter 'limit' must be between 1 and 100");
        return limit.intValue();
    }
}
