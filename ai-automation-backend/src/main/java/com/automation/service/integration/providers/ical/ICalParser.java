package com.automation.service.integration.providers.ical;

import java.time.Duration;
import java.time.Period;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, lenient iCalendar reader for VEVENTs (RFC 5545): unfolds lines, parses content lines and
 * collects the event properties MySuricates uses. Sub-components (VALARM) are skipped, malformed
 * lines and unparsable dates are dropped instead of failing the whole calendar — feeds in the wild
 * are rarely clean. Nothing is expanded; {@code RRULE} is passed through.
 */
public final class ICalParser {

    /** Parsed calendar: its display name ({@code X-WR-CALNAME}, may be null) and its events. */
    public record ICalCalendar(String name, List<ICalEvent> events) {}

    private ICalParser() {}

    public static List<ICalEvent> parseEvents(String text) {
        return parse(text).events();
    }

    public static ICalCalendar parse(String text) {
        List<ICalEvent> events = new ArrayList<>();
        String calendarName = null;
        Deque<String> components = new ArrayDeque<>();
        Map<String, ICalContentLine> current = null;

        for (String raw : unfold(text)) {
            ICalContentLine line = ICalContentLine.parse(raw);
            if (line == null) continue;
            String value = line.value().trim().toUpperCase(Locale.ROOT);
            if ("BEGIN".equals(line.name())) {
                if ("VEVENT".equals(value)) {
                    if (current != null) {
                        // previous VEVENT never ended — keep what it had, drop its open sub-components
                        events.add(toEvent(current));
                        while (!components.isEmpty() && !"VCALENDAR".equals(components.peek())) components.pop();
                    }
                    current = new HashMap<>();
                }
                components.push(value);
                continue;
            }
            if ("END".equals(line.name())) {
                if (!components.isEmpty()) components.pop();
                if ("VEVENT".equals(value) && current != null && !components.contains("VEVENT")) {
                    events.add(toEvent(current));
                    current = null;
                }
                continue;
            }
            if (current != null && "VEVENT".equals(components.peek())) {
                current.putIfAbsent(line.name(), line);
            } else if (components.size() == 1 && "X-WR-CALNAME".equals(line.name())) {
                calendarName = ICalContentLine.unescapeText(line.value());
            }
        }
        return new ICalCalendar(calendarName, events);
    }

    /** Splits into logical lines: CRLF/LF line ends, a leading space or tab continues the previous line. */
    public static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        StringBuilder current = null;
        for (String physical : body.split("\r\n|\n|\r", -1)) {
            if (!physical.isEmpty() && (physical.charAt(0) == ' ' || physical.charAt(0) == '\t')) {
                if (current != null) current.append(physical, 1, physical.length());
                continue;
            }
            if (current != null) lines.add(current.toString());
            current = physical.isEmpty() ? null : new StringBuilder(physical);
        }
        if (current != null) lines.add(current.toString());
        return lines;
    }

    private static ICalEvent toEvent(Map<String, ICalContentLine> props) {
        ICalDateTime start = date(props.get("DTSTART"));
        ICalDateTime end = date(props.get("DTEND"));
        if (end == null && start != null) end = endFromDuration(start, props.get("DURATION"));
        ICalContentLine recurrenceId = props.get("RECURRENCE-ID");
        return new ICalEvent(
                raw(props.get("UID")),
                text(props.get("SUMMARY")),
                text(props.get("DESCRIPTION")),
                text(props.get("LOCATION")),
                raw(props.get("STATUS")),
                start,
                end,
                raw(props.get("RRULE")),
                recurrenceId != null ? recurrenceId.value().trim() : null);
    }

    private static ICalDateTime date(ICalContentLine line) {
        if (line == null) return null;
        try {
            return ICalDateTime.parse(line.value(), line.param("TZID"), line.param("VALUE"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** DTEND from DURATION; an all-day event without either lasts one day (RFC 5545 §3.6.1). */
    private static ICalDateTime endFromDuration(ICalDateTime start, ICalContentLine duration) {
        if (duration == null) {
            return start.allDay() ? ICalDateTime.ofDate(start.date().plusDays(1)) : null;
        }
        try {
            String d = duration.value().trim().toUpperCase(Locale.ROOT);
            boolean negative = d.startsWith("-");
            if (d.startsWith("+") || negative) d = d.substring(1);
            if (d.endsWith("W")) {
                long days = Long.parseLong(d.substring(1, d.length() - 1)) * 7;
                return shift(start, Period.ofDays((int) (negative ? -days : days)), Duration.ZERO);
            }
            int t = d.indexOf('T');
            Period days = t == 1 || d.equals("P") ? Period.ZERO
                    : Period.parse(t < 0 ? d : d.substring(0, t));
            Duration time = t < 0 ? Duration.ZERO : Duration.parse("PT" + d.substring(t + 1));
            return negative ? shift(start, days.negated(), time.negated()) : shift(start, days, time);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ICalDateTime shift(ICalDateTime start, Period days, Duration time) {
        if (start.allDay()) return ICalDateTime.ofDate(start.date().plus(days));
        return new ICalDateTime(null, start.local().plus(days).plus(time), start.zone(), start.tzid());
    }

    private static String raw(ICalContentLine line) {
        return line == null ? null : line.value().trim();
    }

    private static String text(ICalContentLine line) {
        return line == null ? null : ICalContentLine.unescapeText(line.value());
    }
}
