package com.automation.service.integration.providers.ical;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ICalWriterTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00.123Z");

    private static ICalEvent event(ICalDateTime start, ICalDateTime end, String rrule) {
        return new ICalEvent("abc-1@mysuricates", "Titel; mit, Zeichen", "Zeile 1\nZeile 2", "Büro\\3", null,
                start, end, rrule, null);
    }

    @Test
    void calendar_writesAParsableObjectWithCrlfEndings() {
        String ics = ICalWriter.calendar(event(ICalDateTime.fromIso("2026-10-01T10:00", "Europe/Berlin"),
                ICalDateTime.fromIso("2026-10-01T11:00", "Europe/Berlin"), "FREQ=WEEKLY;COUNT=3"), NOW);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:").endsWith("END:VCALENDAR\r\n");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
        assertThat(ics).contains("DTSTAMP:20260924T120000Z\r\n", "DTSTART;TZID=Europe/Berlin:20261001T100000\r\n",
                "SUMMARY:Titel\\; mit\\, Zeichen\r\n", "DESCRIPTION:Zeile 1\\nZeile 2\r\n", "LOCATION:Büro\\\\3\r\n",
                "RRULE:FREQ=WEEKLY;COUNT=3\r\n", "BEGIN:VTIMEZONE\r\nTZID:Europe/Berlin\r\n");
        assertThat(ics.indexOf("BEGIN:VTIMEZONE")).isLessThan(ics.indexOf("BEGIN:VEVENT"));

        ICalEvent back = ICalParser.parseEvents(ics).get(0);
        assertThat(back.summary()).isEqualTo("Titel; mit, Zeichen");
        assertThat(back.description()).isEqualTo("Zeile 1\nZeile 2");
        assertThat(back.location()).isEqualTo("Büro\\3");
        assertThat(back.start().zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(back.rrule()).isEqualTo("FREQ=WEEKLY;COUNT=3");
    }

    @Test
    void calendar_utcAndAllDayNeedNoTimezoneComponent() {
        String utc = ICalWriter.calendar(event(ICalDateTime.fromIso("2026-10-01T08:00:00Z", null), null, null), NOW);
        assertThat(utc).contains("DTSTART:20261001T080000Z").doesNotContain("VTIMEZONE").doesNotContain("RRULE");

        String allDay = ICalWriter.calendar(event(ICalDateTime.fromIso("2026-10-03", "Europe/Berlin"),
                ICalDateTime.fromIso("2026-10-04", "Europe/Berlin"), null), NOW);
        assertThat(allDay).contains("DTSTART;VALUE=DATE:20261003", "DTEND;VALUE=DATE:20261004").doesNotContain("VTIMEZONE");
    }

    @Test
    void fold_limitsEveryPhysicalLineTo75OctetsWithoutSplittingUtf8() {
        String longLine = "SUMMARY:" + "äöü€".repeat(40);
        String folded = ICalWriter.fold(longLine);
        for (String physical : folded.split("\r\n")) {
            assertThat(physical.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(ICalParser.unfold(folded + "\r\n")).containsExactly(longLine);
        assertThat(ICalWriter.fold("SUMMARY:kurz")).isEqualTo("SUMMARY:kurz");
    }

    @Test
    void textWithLineBreaks_cannotInjectProperties() {
        String line = ICalWriter.textLine("SUMMARY", "x\r\nATTENDEE:mailto:evil@example.org");
        assertThat(line).doesNotContain("\r").doesNotContain("\n").isEqualTo("SUMMARY:x\\nATTENDEE:mailto:evil@example.org");
    }

    @Test
    void normalizeRrule_acceptsPrefixAndLowercaseAndRejectsInjection() {
        assertThat(ICalWriter.normalizeRrule("RRULE:freq=daily;count=2")).isEqualTo("FREQ=DAILY;COUNT=2");
        assertThat(ICalWriter.normalizeRrule("FREQ=MONTHLY;BYDAY=-1FR")).isEqualTo("FREQ=MONTHLY;BYDAY=-1FR");
        assertThatThrownBy(() -> ICalWriter.normalizeRrule("FREQ=DAILY\r\nATTENDEE:x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalWriter.normalizeRrule("COUNT=3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalWriter.normalizeRrule("FREQ=DAILY:X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uid_mustBeSafe() {
        ICalEvent bad = new ICalEvent("a\r\nX:y", "s", null, null, null, ICalDateTime.fromIso("2026-10-03", null), null, null, null);
        assertThatThrownBy(() -> ICalWriter.calendar(bad, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vtimezone_matchesTheJdkRulesForBerlin() {
        List<String> lines = ICalTimeZones.vtimezone(ZoneId.of("Europe/Berlin"));
        assertThat(lines).contains("TZID:Europe/Berlin", "BEGIN:DAYLIGHT", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200",
                "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31",
                "DTSTART:19700329T020000", "DTSTART:19701025T030000");
    }

    @Test
    void vtimezone_ruleDaysMatchTheRealTransitionDates() {
        ZoneRules rules = ZoneId.of("America/New_York").getRules();
        List<String> lines = ICalTimeZones.vtimezone(ZoneId.of("America/New_York"));
        // second Sunday of March 2027 = 14th, which lies in BYMONTHDAY=8..14
        assertThat(lines).contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=8,9,10,11,12,13,14");
        assertThat(rules.nextTransition(Instant.parse("2027-01-01T00:00:00Z")).getDateTimeBefore().getDayOfMonth()).isEqualTo(14);
    }

    @Test
    void vtimezone_zoneWithoutDstHasOneStandardBlock() {
        List<String> lines = ICalTimeZones.vtimezone(ZoneId.of("Asia/Kolkata"));
        assertThat(lines).containsSequence("BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:+0530", "TZOFFSETTO:+0530");
        assertThat(ICalTimeZones.offset(java.time.ZoneOffset.ofHoursMinutes(-3, -30))).isEqualTo("-0330");
    }
}
