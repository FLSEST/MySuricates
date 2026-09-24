package com.automation.service.integration.providers.ical;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/** Parameter parsing and I/O helpers shared by the calendar providers — no provider state. */
public final class CalendarParams {

    private CalendarParams() {}

    public static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null && !val.toString().isBlank() ? val.toString() : defaultValue;
    }

    public static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be a number");
            }
        }
        return defaultValue;
    }

    public static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) throw new IllegalArgumentException("Required parameter missing: " + key);
        return val.toString();
    }

    /**
     * Range bound: an ISO instant/offset date-time, or a local date-time / date read in {@code zone}.
     * A date given as {@code to} means "up to and including that day" (start of the following day).
     */
    public static Instant instant(Map<String, Object> params, String key, Instant defaultValue, boolean endOfDay,
                                  ZoneId zone) {
        String value = paramString(params, key, null);
        if (value == null) return defaultValue;
        String v = value.trim();
        try {
            if (v.length() == 10) {
                LocalDate date = LocalDate.parse(v);
                return (endOfDay ? date.plusDays(1) : date).atStartOfDay(zone).toInstant();
            }
            if (v.endsWith("Z") || v.endsWith("z") || v.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return OffsetDateTime.parse(v.replace('z', 'Z')).toInstant();
            }
            return LocalDateTime.parse(v).atZone(zone).toInstant();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Parameter '" + key + "' is not a date/time: " + v);
        }
    }

    /** A time zone parameter; {@code null}/blank gives {@code defaultZone}. */
    public static ZoneId zone(Map<String, Object> params, String key, ZoneId defaultZone) {
        String value = paramString(params, key, null);
        if (value == null) return defaultZone;
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + value);
        }
    }

    /** Reads at most {@code maxBytes}; a longer stream is rejected instead of silently cut. */
    public static byte[] readBounded(InputStream in, long maxBytes, String what) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = stream.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IllegalStateException(what + " exceeds the size limit of " + (maxBytes / (1024 * 1024)) + " MB");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
