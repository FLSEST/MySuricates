package com.automation.service.integration.providers.ical;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Changes single properties of the master VEVENT inside existing iCalendar text and leaves the rest
 * untouched — attendees, alarms, exceptions and vendor properties survive an update, which a
 * parse-and-rewrite would drop. Bumps SEQUENCE, refreshes DTSTAMP/LAST-MODIFIED and adds a
 * VTIMEZONE for every new TZID that the object does not define yet.
 */
public final class ICalPatcher {

    private ICalPatcher() {}

    /**
     * @param ics          the stored calendar object
     * @param removeNames  property names to drop from the master (their replacements are in {@code newLines})
     * @param newLines     unfolded property lines to add to the master
     * @param newZones     zones the new lines reference via TZID
     */
    public static String patch(String ics, Set<String> removeNames, List<String> newLines, Set<ZoneId> newZones, Instant now) {
        List<String> lines = ICalParser.unfold(ics);
        int[] master = findMaster(lines);
        if (master == null) throw new IllegalArgumentException("Calendar object contains no VEVENT");

        Set<String> drop = new HashSet<>();
        for (String n : removeNames) drop.add(n.toUpperCase(Locale.ROOT));
        drop.addAll(Set.of("DTSTAMP", "LAST-MODIFIED", "SEQUENCE"));
        if (drop.contains("DTEND") || drop.contains("DTSTART")) drop.add("DURATION");

        int sequence = 1; // absent SEQUENCE counts as 0
        List<String> kept = new ArrayList<>();
        int depth = 0;
        for (int i = master[0] + 1; i < master[1]; i++) {
            String line = lines.get(i);
            ICalContentLine parsed = ICalContentLine.parse(line);
            if (parsed != null && "BEGIN".equals(parsed.name())) depth++;
            if (depth == 0 && parsed != null) {
                if ("SEQUENCE".equals(parsed.name())) sequence = parseInt(parsed.value()) + 1;
                if (drop.contains(parsed.name())) continue;
            }
            if (parsed != null && "END".equals(parsed.name())) depth--;
            kept.add(line);
        }

        List<String> props = new ArrayList<>();
        props.add(ICalWriter.stampLine(now));
        props.add("LAST-MODIFIED:" + ICalDateTime.ofUtc(now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).toICalValue());
        props.add("SEQUENCE:" + sequence);
        props.addAll(newLines);
        // properties must precede sub-components (VALARM) inside a VEVENT
        int firstSub = 0;
        while (firstSub < kept.size() && !kept.get(firstSub).regionMatches(true, 0, "BEGIN:", 0, 6)) firstSub++;
        kept.addAll(firstSub, props);

        List<String> out = new ArrayList<>(lines.subList(0, master[0] + 1));
        out.addAll(kept);
        out.addAll(lines.subList(master[1], lines.size()));
        insertMissingZones(out, newZones);
        return ICalWriter.join(out);
    }

    /** [BEGIN index, END index] of the first VEVENT without RECURRENCE-ID (else the first VEVENT). */
    static int[] findMaster(List<String> lines) {
        int[] first = null;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).equalsIgnoreCase("BEGIN:VEVENT")) continue;
            int end = i + 1;
            boolean instance = false;
            int depth = 0;
            for (; end < lines.size(); end++) {
                String l = lines.get(end);
                if (l.regionMatches(true, 0, "BEGIN:", 0, 6)) depth++;
                else if (l.equalsIgnoreCase("END:VEVENT") && depth == 0) break;
                else if (l.regionMatches(true, 0, "END:", 0, 4)) depth--;
                else if (depth == 0 && l.regionMatches(true, 0, "RECURRENCE-ID", 0, 13)) instance = true;
            }
            if (end >= lines.size()) return first;
            if (!instance) return new int[]{i, end};
            if (first == null) first = new int[]{i, end};
            i = end;
        }
        return first;
    }

    private static void insertMissingZones(List<String> lines, Set<ZoneId> zones) {
        if (zones.isEmpty()) return;
        Set<String> defined = new HashSet<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).equalsIgnoreCase("BEGIN:VTIMEZONE")) {
                ICalContentLine tzid = ICalContentLine.parse(lines.get(i + 1));
                if (tzid != null && "TZID".equals(tzid.name())) defined.add(tzid.value().trim());
            }
        }
        int at = 0;
        while (at < lines.size() && !lines.get(at).equalsIgnoreCase("BEGIN:VEVENT")) at++;
        for (ZoneId zone : zones) {
            if (defined.contains(zone.getId())) continue;
            List<String> vtz = ICalTimeZones.vtimezone(zone);
            lines.addAll(at, vtz);
            at += vtz.size();
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
