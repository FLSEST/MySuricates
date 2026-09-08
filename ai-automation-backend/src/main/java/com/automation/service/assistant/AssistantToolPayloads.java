package com.automation.service.assistant;

/**
 * Small pure helpers for assistant tool payloads: id shortening for user-facing text and the
 * fullwidth-Unicode normalisation Jackson needs before parsing model-generated JSON.
 *
 * <p>Extracted from {@code AssistantToolExecutor} (Audit-TODO 9f166f55).
 */
public final class AssistantToolPayloads {

    private AssistantToolPayloads() {
    }

    public static String shorten(Object id) {
        if (id == null) return "?";
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) + "…" : s;
    }

    /**
     * Normalizes fullwidth Unicode punctuation to ASCII equivalents.
     * Claude occasionally generates these characters in JSON payloads,
     * which Jackson's parser rejects.
     */
    public static String sanitizeJson(String json) {
        if (json == null || json.isEmpty()) return json;
        return json
                .replace('，', ',')   // ， → ,
                .replace('：', ':')   // ： → :
                .replace('；', ';')   // ； → ;
                .replace('｛', '{')   // ｛ → {
                .replace('｝', '}')   // ｝ → }
                .replace('［', '[')   // ［ → [
                .replace('］', ']')   // ］ → ]
                .replace('＂', '"')   // ＂ → "
                .replace('“', '"')   // " → "
                .replace('”', '"');  // " → "
    }
}
