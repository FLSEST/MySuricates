package com.automation.service.wish;

import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
final class WishJsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WishJsonUtil() {}

    static Map<String, Object> parseJsonSafely(String responseText, String context) {
        String json = extractJson(responseText);
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("JSON parse failed in {} ({}), attempting repair", context, e.getClass().getSimpleName());
            String repaired = JsonRepairUtil.repairTruncatedJson(json);
            if (repaired != null) {
                try {
                    Map<String, Object> result = MAPPER.readValue(repaired, new TypeReference<>() {});
                    log.warn("JSON repair succeeded in {} (repaired {} chars → {} chars)", context, json.length(), repaired.length());
                    return result;
                } catch (Exception e2) {
                    // repair produced valid JSON tree but not a valid map — fall through
                }
            }
            throw new IllegalStateException("Claude returned unparseable JSON in " + context
                    + ". First 300 chars: " + WishStringUtil.truncate(responseText, 300));
        }
    }

    static String extractJson(String text) {
        if (text == null) return "{}";

        // Strip markdown code fences if present
        String cleaned = text;
        int fenceStart = cleaned.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = cleaned.indexOf('\n', fenceStart);
            int fenceEnd = cleaned.indexOf("```", contentStart > 0 ? contentStart : fenceStart + 3);
            if (contentStart > 0 && fenceEnd > contentStart) {
                cleaned = cleaned.substring(contentStart + 1, fenceEnd).trim();
            }
        }

        // Find first { and last } to extract JSON
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        // No JSON found — log the raw response for debugging
        log.warn("No JSON object found in Claude response (first 200 chars): {}",
                WishStringUtil.truncate(text, 200));
        throw new IllegalStateException("Claude did not return valid JSON. Response: " + WishStringUtil.truncate(text, 300));
    }
}
