package com.automation.service.integration.providers.ical;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An iCalendar DATE or DATE-TIME value (RFC 5545 §3.3.4/§3.3.5) in one of its four forms:
 * all-day date, UTC ({@code …Z}), local time with a TZID, or floating local time.
 *
 * <p>{@code zone} is the resolved Java zone of a TZID (or UTC); it is {@code null} for a floating
 * time and for a TZID that java.time does not know (e.g. a Windows zone name) — {@code tzid} then
 * still carries the raw name so nothing is silently lost.
 */
public record ICalDateTime(LocalDate date, LocalDateTime local, ZoneId zone, String tzid) {

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter BASIC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    public static ICalDateTime ofDate(LocalDate date) {
        return new ICalDateTime(date, null, null, null);
    }

    public static ICalDateTime ofUtc(Instant instant) {
        return new ICalDateTime(null, LocalDateTime.ofInstant(instant, ZoneOffset.UTC), ZoneOffset.UTC, null);
    }

    public static ICalDateTime ofZoned(LocalDateTime local, ZoneId zone) {
        return new ICalDateTime(null, local, zone, zone.getId());
    }

    public boolean allDay() {
        return date != null;
    }

    public boolean utc() {
        return date == null && ZoneOffset.UTC.equals(zone) && tzid == null;
    }

    /**
     * Parses a property value. {@code valueType} is the VALUE parameter ({@code DATE} marks an
     * all-day value), {@code tzidParam} the TZID parameter. Throws {@link IllegalArgumentException}
     * on a malformed value.
     */
    public static ICalDateTime parse(String value, String tzidParam, String valueType) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("empty date value");
        String v = value.trim();
        try {
            if ("DATE".equalsIgnoreCase(valueType) || (v.length() == 8 && v.chars().allMatch(Character::isDigit))) {
                return ofDate(LocalDate.parse(v.substring(0, 8), BASIC_DATE));
            }
            if (v.endsWith("Z") || v.endsWith("z")) {
                return new ICalDateTime(null, LocalDateTime.parse(v.substring(0, v.length() - 1), BASIC_DATE_TIME),
                        ZoneOffset.UTC, null);
            }
            LocalDateTime local = LocalDateTime.parse(v, BASIC_DATE_TIME);
            if (tzidParam == null || tzidParam.isBlank()) return new ICalDateTime(null, local, null, null);
            return new ICalDateTime(null, local, resolveZone(tzidParam), tzidParam);
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("malformed date value: " + v, e);
        }
    }

    /**
     * Parses user input for writing: {@code 2026-09-24} (all-day), an ISO date-time with {@code Z} or
     * offset (stored as UTC), or a local ISO date-time interpreted in {@code timeZone}.
     */
    public static ICalDateTime fromIso(String iso, String timeZone) {
        if (iso == null || iso.isBlank()) throw new IllegalArgumentException("empty date/time");
        String v = iso.trim();
        try {
            if (v.length() == 10) return ofDate(LocalDate.parse(v));
            if (v.endsWith("Z") || v.endsWith("z") || v.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return ofUtc(OffsetDateTime.parse(v.replace('z', 'Z')).toInstant());
            }
            LocalDateTime local = LocalDateTime.parse(v);
            ZoneId zone = timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone.trim());
            return ZoneOffset.UTC.equals(zone.normalized()) ? ofUtc(local.toInstant(ZoneOffset.UTC)) : ofZoned(local, zone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid date/time '" + v + "' — expected e.g. 2026-09-24, "
                    + "2026-09-24T10:00:00 or 2026-09-24T08:00:00Z", e);
        }
    }

    /** Point in time; all-day and floating values are placed in {@code fallback}. */
    public Instant toInstant(ZoneId fallback) {
        if (date != null) return date.atStartOfDay(fallback).toInstant();
        return local.atZone(zone != null ? zone : fallback).toInstant();
    }

    /** Value as written in an iCalendar property (without the TZID parameter). */
    public String toICalValue() {
        if (date != null) return date.format(BASIC_DATE);
        return local.format(BASIC_DATE_TIME) + (utc() ? "Z" : "");
    }

    /** Property line prefix incl. parameters, e.g. {@code DTSTART;TZID=Europe/Berlin}. */
    public String propertyName(String name) {
        if (date != null) return name + ";VALUE=DATE";
        if (tzid != null) return name + ";TZID=" + tzid;
        return name;
    }

    /** JSON form in the shape Google Calendar uses: {@code {date}} or {@code {dateTime, timeZone}}. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (date != null) {
            map.put("date", date.toString());
            return map;
        }
        if (zone != null) {
            OffsetDateTime odt = local.atZone(zone).toOffsetDateTime();
            map.put("dateTime", odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            map.put("timeZone", tzid != null ? tzid : "UTC");
        } else {
            map.put("dateTime", local.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            if (tzid != null) map.put("timeZone", tzid);
        }
        return map;
    }

    /**
     * Resolves a TZID to a java.time zone. Accepts plain Olson ids and the prefixed forms some clients
     * write ({@code /mozilla.org/20050126_1/Europe/Berlin}); unknown names give {@code null}.
     */
    static ZoneId resolveZone(String tzid) {
        String id = tzid.trim();
        if (id.startsWith("\"") && id.endsWith("\"") && id.length() > 1) id = id.substring(1, id.length() - 1);
        try {
            return ZoneId.of(id);
        } catch (DateTimeException ignored) {
            // fall through to the suffix search
        }
        String[] parts = id.split("/");
        for (int take = Math.min(3, parts.length); take >= 1; take--) {
            String candidate = String.join("/", java.util.Arrays.copyOfRange(parts, parts.length - take, parts.length));
            try {
                return ZoneId.of(candidate);
            } catch (DateTimeException ignored) {
                // try a shorter suffix
            }
        }
        return null;
    }
}
