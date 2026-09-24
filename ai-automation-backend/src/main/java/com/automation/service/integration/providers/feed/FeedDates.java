package com.automation.service.integration.providers.feed;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Date parsing for feeds: RFC 822/1123 (RSS {@code pubDate}, lenient about weekday, seconds,
 * two-digit years and zone names) and ISO 8601 (Atom, Dublin Core, the {@code since} parameter).
 * Everything is normalised to a UTC {@link Instant}; unparseable input yields {@code null}.
 */
final class FeedDates {

    private static final Pattern RFC822 = Pattern.compile(
            "^(?:[A-Za-z]{2,},?\\s*)?(\\d{1,2})\\s+([A-Za-z]{3})[A-Za-z]*\\.?\\s+(\\d{2,4})"
                    + "\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(\\S+)?$");

    private static final Pattern NUMERIC_ZONE = Pattern.compile("^[+-]\\d{2}:?\\d{2}$");

    private static final List<String> MONTHS = List.of(
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    private static final Map<String, Integer> NAMED_ZONES_HOURS = Map.ofEntries(
            Map.entry("GMT", 0), Map.entry("UT", 0), Map.entry("UTC", 0), Map.entry("Z", 0),
            Map.entry("EST", -5), Map.entry("EDT", -4), Map.entry("CST", -6), Map.entry("CDT", -5),
            Map.entry("MST", -7), Map.entry("MDT", -6), Map.entry("PST", -8), Map.entry("PDT", -7),
            Map.entry("CET", 1), Map.entry("CEST", 2), Map.entry("MEZ", 1), Map.entry("MESZ", 2));

    private FeedDates() {}

    static Instant parse(String value) {
        if (value == null || value.length() > 100) return null;
        String s = value.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        Instant iso = parseIso(s);
        return iso != null ? iso : parseRfc822(s);
    }

    private static Instant parseIso(String s) {
        String t = s.matches("^\\d{4}-\\d{2}-\\d{2} \\d.*") ? s.replaceFirst(" ", "T") : s;
        t = t.replaceFirst("(T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?) ?([+-]\\d{2})(\\d{2})$", "$1$2:$3");
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeException ignored) {
            // try the zone-less forms below
        }
        try {
            return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            // try a plain date
        }
        try {
            return LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static Instant parseRfc822(String s) {
        Matcher m = RFC822.matcher(s);
        if (!m.matches()) return null;
        int month = MONTHS.indexOf(m.group(2).toLowerCase(Locale.ROOT)) + 1;
        if (month == 0) return null;
        int year = Integer.parseInt(m.group(3));
        if (m.group(3).length() == 2) year += year < 50 ? 2000 : 1900;
        else if (m.group(3).length() == 3) return null;
        try {
            LocalDateTime local = LocalDateTime.of(year, month, Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    m.group(6) != null ? Integer.parseInt(m.group(6)) : 0);
            return local.toInstant(zone(m.group(7)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Numeric offsets and the RFC 822 zone names; unknown names count as UTC rather than dropping the date. */
    private static ZoneOffset zone(String zone) {
        if (zone == null) return ZoneOffset.UTC;
        if (NUMERIC_ZONE.matcher(zone).matches()) {
            String z = zone.contains(":") ? zone : zone.substring(0, 3) + ":" + zone.substring(3);
            return ZoneOffset.of(z);
        }
        Integer hours = NAMED_ZONES_HOURS.get(zone.toUpperCase(Locale.ROOT));
        return hours != null ? ZoneOffset.ofHours(hours) : ZoneOffset.UTC;
    }
}
