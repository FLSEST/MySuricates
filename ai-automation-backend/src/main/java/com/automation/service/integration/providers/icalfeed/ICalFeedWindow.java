package com.automation.service.integration.providers.icalfeed;

import com.automation.service.integration.providers.ical.ICalDateTime;
import com.automation.service.integration.providers.ical.ICalEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the events of a feed that concern a time window, without expanding series: single events
 * (and overridden instances) that overlap [from, to), plus series masters that have started before
 * {@code to} and are not over before {@code from} (UNTIL). Masters carry {@code recurring: true}
 * and their {@code rrule} — the caller decides what a series means for its window.
 */
final class ICalFeedWindow {

    static final String SERIES_NOTE = "Serien werden nicht expandiert: Serien-Master stehen mit 'rrule' und "
            + "'recurring: true' in der Liste, ihr start ist das erste Vorkommen. EXDATE/Ausnahmen bleiben unberücksichtigt.";

    private ICalFeedWindow() {}

    static List<Map<String, Object>> select(List<ICalEvent> events, Instant from, Instant to, ZoneId zone) {
        List<ICalEvent> hits = new ArrayList<>();
        for (ICalEvent e : events) {
            if (e.start() == null) continue;
            if (e.recurring() ? seriesTouches(e, from, to, zone) : overlaps(e, from, to, zone)) hits.add(e);
        }
        hits.sort(Comparator.comparing(e -> e.start().toInstant(zone)));
        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (ICalEvent e : hits) {
            Map<String, Object> map = e.toMap();
            if (e.recurring()) map.put("recurring", true);
            out.add(map);
        }
        return out;
    }

    static boolean overlaps(ICalEvent e, Instant from, Instant to, ZoneId zone) {
        Instant start = e.start().toInstant(zone);
        Instant end = e.end() != null ? e.end().toInstant(zone) : start;
        if (!start.isBefore(to)) return false;
        return end.equals(start) ? !start.isBefore(from) : end.isAfter(from);
    }

    static boolean seriesTouches(ICalEvent e, Instant from, Instant to, ZoneId zone) {
        if (!e.start().toInstant(zone).isBefore(to)) return false;
        Instant until = until(e.rrule(), zone);
        return until == null || !until.isBefore(from);
    }

    /** UNTIL of a rule as an instant, {@code null} when absent or unreadable (then: open-ended). */
    static Instant until(String rrule, ZoneId zone) {
        for (String part : rrule.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].trim().toUpperCase(Locale.ROOT).equals("UNTIL")) {
                try {
                    ICalDateTime value = ICalDateTime.parse(kv[1], null, null);
                    Instant start = value.toInstant(value.utc() ? ZoneOffset.UTC : zone);
                    return value.allDay() ? start.plusSeconds(86_399) : start;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
