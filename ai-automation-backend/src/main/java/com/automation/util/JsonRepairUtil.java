package com.automation.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Attempts to repair truncated JSON responses from Claude CLI.
 * Handles common truncation patterns: unclosed strings, arrays, objects, and trailing commas.
 */
public final class JsonRepairUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRepairUtil() {}

    /**
     * Attempts to repair truncated JSON by closing unclosed brackets/strings
     * and removing trailing commas.
     *
     * @return repaired JSON string, or null if repair fails validation
     */
    public static String repairTruncatedJson(String json) {
        if (json == null || json.isBlank()) return null;

        String trimmed = json.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;

        StringBuilder result = new StringBuilder(trimmed);

        // Track open brackets with string-awareness
        boolean inString = false;
        boolean escaped = false;
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();

        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                if (inString) {
                    stack.push('"');
                } else {
                    if (!stack.isEmpty() && stack.peek() == '"') {
                        stack.pop();
                    }
                }
                continue;
            }

            if (inString) continue;

            if (c == '{') stack.push('{');
            else if (c == '[') stack.push('[');
            else if (c == '}') {
                if (!stack.isEmpty() && stack.peek() == '{') stack.pop();
            } else if (c == ']') {
                if (!stack.isEmpty() && stack.peek() == '[') stack.pop();
            }
        }

        // Close unclosed string first
        if (inString) {
            result.append('"');
            if (!stack.isEmpty() && stack.peek() == '"') {
                stack.pop();
            }
        }

        // Remove trailing commas before closing brackets
        removeTrailingComma(result);

        // Close remaining open brackets in reverse order
        while (!stack.isEmpty()) {
            char open = stack.pop();
            removeTrailingComma(result);
            if (open == '{') result.append('}');
            else if (open == '[') result.append(']');
        }

        // Validate the repaired JSON
        String repaired = result.toString();
        if (isValidJsonObject(repaired)) {
            return repaired;
        }
        return null;
    }

    /**
     * Checks whether the given string is valid JSON (object or array).
     */
    public static boolean isValidJsonObject(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void removeTrailingComma(StringBuilder sb) {
        int i = sb.length() - 1;
        while (i >= 0 && Character.isWhitespace(sb.charAt(i))) i--;
        if (i >= 0 && sb.charAt(i) == ',') {
            sb.deleteCharAt(i);
        }
    }
}
