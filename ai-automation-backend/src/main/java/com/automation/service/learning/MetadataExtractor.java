package com.automation.service.learning;

import java.util.Map;

public final class MetadataExtractor {

    private MetadataExtractor() {}

    public static double extractDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    public static long extractLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return 0;
    }

    public static String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof String s) return s;
        return null;
    }
}
