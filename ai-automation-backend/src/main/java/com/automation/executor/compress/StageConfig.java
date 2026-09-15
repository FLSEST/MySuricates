package com.automation.executor.compress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed, validated access to block and stage config values with German error messages. */
public final class StageConfig {

    private StageConfig() {
    }

    public static String where(String op) {
        return "Stufe " + op;
    }

    public static int intValue(Map<String, Object> c, String key, Integer fallback, int min, int max, String where) {
        Object raw = c == null ? null : c.get(key);
        if (raw == null) {
            if (fallback != null) return fallback;
            throw new CompressionException(where + ": '" + key + "' fehlt");
        }
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.longValue() < min || n.longValue() > max) {
            throw new CompressionException(where + ": '" + key + "' muss eine ganze Zahl zwischen "
                    + CompressionNumbers.fmt(min) + " und " + CompressionNumbers.fmt(max) + " sein");
        }
        return n.intValue();
    }

    public static String string(Map<String, Object> c, String key, String fallback, String where) {
        Object raw = c == null ? null : c.get(key);
        if (raw == null) {
            if (fallback != null) return fallback;
            throw new CompressionException(where + ": '" + key + "' fehlt");
        }
        if (!(raw instanceof String s)) {
            throw new CompressionException(where + ": '" + key + "' muss Text sein");
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
        throw new CompressionException(where + ": '" + key + "' muss eine Liste von Texten sein");
    }

    public static boolean flag(Map<String, Object> c, String key) {
        return Boolean.TRUE.equals(c == null ? null : c.get(key));
    }
}
