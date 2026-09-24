package com.automation.service.integration.providers.ical;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes iCalendar text: a complete VCALENDAR with one VEVENT (plus VTIMEZONE for every TZID used),
 * TEXT escaping and line folding at 75 octets (RFC 5545 §3.1, UTF-8 safe). Every user-supplied value
 * goes through {@link ICalContentLine#escapeText} or a whitelist, so no parameter can inject lines.
 */
public final class ICalWriter {

    public static final String PRODID = "-//MySuricates//Integration//EN";
    private static final int MAX_OCTETS = 75;

    private ICalWriter() {}

    /** A new calendar object resource with the given event. */
    public static String calendar(ICalEvent event, Instant dtstamp) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:" + PRODID);
        lines.add("CALSCALE:GREGORIAN");
        for (ZoneId zone : zonesOf(event.start(), event.end())) lines.addAll(ICalTimeZones.vtimezone(zone));
        lines.add("BEGIN:VEVENT");
        lines.add("UID:" + requireSafeUid(event.uid()));
        lines.add(stampLine(dtstamp));
        lines.addAll(eventProperties(event));
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");
        return join(lines);
    }

    /** DTSTART/DTEND/SUMMARY/DESCRIPTION/LOCATION/RRULE of an event, skipping absent ones. */
    public static List<String> eventProperties(ICalEvent event) {
        List<String> lines = new ArrayList<>();
        if (event.start() != null) lines.add(dateLine("DTSTART", event.start()));
        if (event.end() != null) lines.add(dateLine("DTEND", event.end()));
        if (event.summary() != null) lines.add(textLine("SUMMARY", event.summary()));
        if (event.description() != null) lines.add(textLine("DESCRIPTION", event.description()));
        if (event.location() != null) lines.add(textLine("LOCATION", event.location()));
        if (event.rrule() != null && !event.rrule().isBlank()) lines.add("RRULE:" + normalizeRrule(event.rrule()));
        return lines;
    }

    public static String dateLine(String name, ICalDateTime value) {
        return value.propertyName(name) + ":" + value.toICalValue();
    }

    public static String textLine(String name, String value) {
        return name + ":" + ICalContentLine.escapeText(value);
    }

    public static String stampLine(Instant instant) {
        return "DTSTAMP:" + ICalDateTime.ofUtc(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).toICalValue();
    }

    /** Zones that need a VTIMEZONE (TZID values), in order of first use. */
    public static Set<ZoneId> zonesOf(ICalDateTime... values) {
        Set<ZoneId> zones = new LinkedHashSet<>();
        for (ICalDateTime v : values) {
            if (v != null && v.tzid() != null && v.zone() != null && !v.allDay()) zones.add(v.zone());
        }
        return zones;
    }

    /**
     * Accepts {@code FREQ=WEEKLY;BYDAY=MO,WE} (optionally with an {@code RRULE:} prefix). Only the
     * characters a recurrence rule consists of are allowed, and FREQ is mandatory.
     */
    public static String normalizeRrule(String rrule) {
        String r = rrule.trim();
        if (r.regionMatches(true, 0, "RRULE:", 0, 6)) r = r.substring(6);
        r = r.toUpperCase(Locale.ROOT);
        if (!r.matches("[A-Z0-9=;,+\\-]+") || !r.matches("(^|.*;)FREQ=[A-Z]+(;.*|$)")) {
            throw new IllegalArgumentException("Invalid rrule '" + rrule + "' — expected e.g. FREQ=WEEKLY;BYDAY=MO");
        }
        return r;
    }

    static String requireSafeUid(String uid) {
        if (uid == null || !uid.matches("[A-Za-z0-9@._\\-]+")) {
            throw new IllegalArgumentException("Invalid event UID");
        }
        return uid;
    }

    /** Folds every line and joins with CRLF, including the trailing line break. */
    public static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(fold(line)).append("\r\n");
        return out.toString();
    }

    /** Folds a line to at most 75 octets per physical line without splitting a UTF-8 sequence. */
    public static String fold(String line) {
        if (line.getBytes(StandardCharsets.UTF_8).length <= MAX_OCTETS) return line;
        StringBuilder out = new StringBuilder();
        int octets = 0;
        int limit = MAX_OCTETS;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (octets + size > limit) {
                out.append("\r\n ");
                octets = 0;
                limit = MAX_OCTETS - 1; // continuation lines start with the folding space
            }
            out.appendCodePoint(cp);
            octets += size;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
