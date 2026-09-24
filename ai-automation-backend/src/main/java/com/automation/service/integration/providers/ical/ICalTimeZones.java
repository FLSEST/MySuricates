package com.automation.service.integration.providers.ical;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Builds a VTIMEZONE component from the JDK's tz rules, so every TZID we write is defined in the
 * same object as RFC 5545 §3.6.5 requires. Covers the zone's current yearly rules (DST start/end);
 * historical offsets are not reproduced — only events written from now on use the component.
 */
public final class ICalTimeZones {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private ICalTimeZones() {}

    /** Unfolded VTIMEZONE lines ({@code BEGIN:VTIMEZONE} … {@code END:VTIMEZONE}). */
    public static List<String> vtimezone(ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VTIMEZONE");
        lines.add("TZID:" + zone.getId());
        List<ZoneOffsetTransitionRule> transitions = rules.getTransitionRules();
        if (transitions.isEmpty()) {
            ZoneOffset offset = rules.getOffset(Instant.now());
            lines.add("BEGIN:STANDARD");
            lines.add("DTSTART:19700101T000000");
            lines.add("TZOFFSETFROM:" + offset(offset));
            lines.add("TZOFFSETTO:" + offset(offset));
            lines.add("END:STANDARD");
        }
        for (ZoneOffsetTransitionRule rule : transitions) {
            ZoneOffsetTransition sample = rule.createTransition(1970);
            String kind = rule.getOffsetAfter().getTotalSeconds() > rule.getOffsetBefore().getTotalSeconds()
                    ? "DAYLIGHT" : "STANDARD";
            lines.add("BEGIN:" + kind);
            lines.add("DTSTART:" + sample.getDateTimeBefore().format(LOCAL));
            lines.add("RRULE:" + yearlyRule(rule));
            lines.add("TZOFFSETFROM:" + offset(rule.getOffsetBefore()));
            lines.add("TZOFFSETTO:" + offset(rule.getOffsetAfter()));
            lines.add("END:" + kind);
        }
        lines.add("END:VTIMEZONE");
        return lines;
    }

    /**
     * "Weekday on or after day d" is exactly {@code BYDAY=SU;BYMONTHDAY=d..d+6}; "on or before the
     * n-th last day" uses the negative month days. No approximation via {@code 2SU}/{@code -1SU} needed.
     */
    static String yearlyRule(ZoneOffsetTransitionRule rule) {
        StringBuilder r = new StringBuilder("FREQ=YEARLY;BYMONTH=").append(rule.getMonth().getValue());
        int indicator = rule.getDayOfMonthIndicator();
        DayOfWeek dow = rule.getDayOfWeek();
        if (dow == null) {
            return r.append(";BYMONTHDAY=").append(indicator).toString();
        }
        StringJoiner days = new StringJoiner(",");
        for (int i = 0; i < 7; i++) days.add(String.valueOf(indicator > 0 ? indicator + i : indicator - i));
        return r.append(";BYDAY=").append(dow.name(), 0, 2).append(";BYMONTHDAY=").append(days).toString();
    }

    static String offset(ZoneOffset offset) {
        int total = offset.getTotalSeconds();
        int abs = Math.abs(total);
        return String.format("%s%02d%02d", total < 0 ? "-" : "+", abs / 3600, (abs % 3600) / 60);
    }
}
