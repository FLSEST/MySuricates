package com.automation.service.integration.providers.ical;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransitionRule;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ICalTimeZonesTest {

    private static ZoneOffsetTransitionRule rule(String zone, int index) {
        return ZoneId.of(zone).getRules().getTransitionRules().get(index);
    }

    @Test
    void vtimezone_berlinHasDaylightAndStandardWithYearlyRules() {
        List<String> lines = ICalTimeZones.vtimezone(ZoneId.of("Europe/Berlin"));

        assertThat(lines).startsWith("BEGIN:VTIMEZONE", "TZID:Europe/Berlin").endsWith("END:VTIMEZONE");
        assertThat(lines).contains("BEGIN:DAYLIGHT", "END:DAYLIGHT", "BEGIN:STANDARD", "END:STANDARD",
                "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31",
                "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31");
        int daylight = lines.indexOf("BEGIN:DAYLIGHT");
        assertThat(lines.subList(daylight, daylight + 7)).contains("TZOFFSETFROM:+0100", "TZOFFSETTO:+0200");
        int standard = lines.indexOf("BEGIN:STANDARD");
        assertThat(lines.subList(standard, standard + 7)).contains("TZOFFSETFROM:+0200", "TZOFFSETTO:+0100");
    }

    @Test
    void vtimezone_zoneWithoutDst_getsSingleStandardWithCurrentOffset() {
        List<String> lines = ICalTimeZones.vtimezone(ZoneId.of("Asia/Kolkata"));

        assertThat(lines).containsExactly("BEGIN:VTIMEZONE", "TZID:Asia/Kolkata", "BEGIN:STANDARD",
                "DTSTART:19700101T000000", "TZOFFSETFROM:+0530", "TZOFFSETTO:+0530", "END:STANDARD", "END:VTIMEZONE");
    }

    @Test
    void vtimezone_dtstartIsTheLocalTimeBeforeTheTransition() {
        List<String> lines = ICalTimeZones.vtimezone(ZoneId.of("Europe/Berlin"));
        // 1970-03-29 02:00 local wall time before the switch to CEST
        assertThat(lines).anyMatch(l -> l.matches("DTSTART:1970\\d{4}T020000"));
    }

    @Test
    void yearlyRule_negativeIndicatorCountsFromTheEndOfTheMonth() {
        // Europe/Paris-style "last Sunday" is 25..31 in the JDK; a negative indicator uses negative month days
        ZoneOffsetTransitionRule last = ZoneOffsetTransitionRule.of(java.time.Month.OCTOBER, -1, java.time.DayOfWeek.SUNDAY,
                java.time.LocalTime.of(3, 0), false, ZoneOffsetTransitionRule.TimeDefinition.WALL,
                ZoneOffset.ofHours(2), ZoneOffset.ofHours(2), ZoneOffset.ofHours(1));
        assertThat(ICalTimeZones.yearlyRule(last)).isEqualTo("FREQ=YEARLY;BYMONTH=10;BYDAY=SU;BYMONTHDAY=-1,-2,-3,-4,-5,-6,-7");
        ZoneOffsetTransitionRule fixedDay = ZoneOffsetTransitionRule.of(java.time.Month.JUNE, 15, null,
                java.time.LocalTime.MIDNIGHT, false, ZoneOffsetTransitionRule.TimeDefinition.WALL,
                ZoneOffset.UTC, ZoneOffset.UTC, ZoneOffset.ofHours(1));
        assertThat(ICalTimeZones.yearlyRule(fixedDay)).isEqualTo("FREQ=YEARLY;BYMONTH=6;BYMONTHDAY=15");
    }

    @Test
    void yearlyRule_usaSecondSundayInMarchCountsFromTheEighth() {
        assertThat(ICalTimeZones.yearlyRule(rule("America/New_York", 0)))
                .isEqualTo("FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=8,9,10,11,12,13,14");
    }

    @Test
    void offset_formatsSignHoursAndMinutes() {
        assertThat(ICalTimeZones.offset(ZoneOffset.UTC)).isEqualTo("+0000");
        assertThat(ICalTimeZones.offset(ZoneOffset.ofHours(2))).isEqualTo("+0200");
        assertThat(ICalTimeZones.offset(ZoneOffset.ofHoursMinutes(-3, -30))).isEqualTo("-0330");
        assertThat(ICalTimeZones.offset(ZoneOffset.ofHoursMinutes(5, 45))).isEqualTo("+0545");
    }
}
