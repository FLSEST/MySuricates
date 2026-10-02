package com.automation.executor.compress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed, validated access to block and stage config values with German error messages. */
public final class StageConfig {

    private StageConfig() {
    }

    public static String where(String op) {
        return "Stage " + op;
    }

    public static int intValue(Map<String, Object> c, String key, Integer fallback, int min, int max, String where) {
        Object raw = c == null ? null : c.get(key);
        if (raw == null) {
            if (fallback != null) return fallback;
            throw new CompressionException(where + ": '" + key + "' is missing");
        }
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.longValue() < min || n.longValue() > max) {
            throw new CompressionException(where + ": '" + key + "' must be a whole number between "
                    + CompressionNumbers.fmt(min) + " and " + CompressionNumbers.fmt(max));
        }
        return n.intValue();
    }

    public static String string(Map<String, Object> c, String key, String fallback, String where) {
        Object raw = c == null ? null : c.get(key);
        if (raw == null) {
            if (fallback != null) return fallback;
            throw new CompressionException(where + ": '" + key + "' is missing");
        }
        if (!(raw instanceof String s)) {
            throw new CompressionException(where + ": '" + key + "' must be text");
        }
        return s;
    }

    public static List<String> strings(Map<String, Object> c, String key, String where) {
        Object raw = c == null ? null : c.get(key);
        if (raw == null) return List.of();
        if (raw instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            List<String> out = new ArrayList<>();
            list.forEach(v -> out.add((String) v));
            return out;
        }
        throw new CompressionException(where + ": '" + key + "' must be a list of texts");
    }

    public static boolean flag(Map<String, Object> c, String key) {
        return Boolean.TRUE.equals(c == null ? null : c.get(key));
    }
}
