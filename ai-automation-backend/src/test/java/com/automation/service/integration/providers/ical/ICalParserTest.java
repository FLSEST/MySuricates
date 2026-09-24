package com.automation.service.integration.providers.ical;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ICalParserTest {

    private static String ics(String... lines) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + String.join("\r\n", lines) + "\r\nEND:VCALENDAR\r\n";
    }

    private static ICalEvent single(String... lines) {
        List<ICalEvent> events = ICalParser.parseEvents(ics(lines));
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    @Test
    void unfold_joinsContinuationLinesWithSpaceOrTab() {
        assertThat(ICalParser.unfold("SUMMARY:Lan\r\n g\r\n\tes Meeting\r\nUID:1\r\n"))
                .containsExactly("SUMMARY:Langes Meeting", "UID:1");
    }

    @Test
    void unfold_acceptsBareLfAndBomAndSkipsEmptyLines() {
        assertThat(ICalParser.unfold("﻿BEGIN:VCALENDAR\n\nSUMMARY:a\n b\nEND:VCALENDAR"))
                .containsExactly("BEGIN:VCALENDAR", "SUMMARY:ab", "END:VCALENDAR");
    }

    @Test
    void unfold_keepsMultiByteCharactersSplitAcrossFoldedLines() {
        ICalEvent e = single("BEGIN:VEVENT", "UID:1", "SUMMARY:Grü", " ße aus Köln", "DTSTART:20261001T100000Z", "END:VEVENT");
        assertThat(e.summary()).isEqualTo("Grüße aus Köln");
    }

    @Test
    void textValues_areUnescaped() {
        ICalEvent e = single("BEGIN:VEVENT", "UID:1", "DTSTART:20261001T100000Z",
                "SUMMARY:A\\, B\\; C \\\\ D", "DESCRIPTION:Zeile 1\\nZeile 2\\NZeile 3", "LOCATION:Raum\\, 3", "END:VEVENT");
        assertThat(e.summary()).isEqualTo("A, B; C \\ D");
        assertThat(e.description()).isEqualTo("Zeile 1\nZeile 2\nZeile 3");
        assertThat(e.location()).isEqualTo("Raum, 3");
    }

    @Test
    void dates_utcTzidFloatingAndAllDay() {
        ICalEvent utc = single("BEGIN:VEVENT", "UID:u", "DTSTART:20261001T080000Z", "DTEND:20261001T090000Z", "END:VEVENT");
        assertThat(utc.start().utc()).isTrue();
        assertThat(utc.start().toMap()).containsEntry("dateTime", "2026-10-01T08:00:00Z").containsEntry("timeZone", "UTC");

        ICalEvent zoned = single("BEGIN:VEVENT", "UID:z", "DTSTART;TZID=Europe/Berlin:20261001T100000",
                "DTEND;TZID=Europe/Berlin:20261001T113000", "END:VEVENT");
        assertThat(zoned.start().zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(zoned.start().toMap()).containsEntry("dateTime", "2026-10-01T10:00:00+02:00")
                .containsEntry("timeZone", "Europe/Berlin");
        assertThat(zoned.end().local()).isEqualTo(LocalDateTime.of(2026, 10, 1, 11, 30));

        ICalEvent floating = single("BEGIN:VEVENT", "UID:f", "DTSTART:20261001T100000", "END:VEVENT");
        assertThat(floating.start().zone()).isNull();
        assertThat(floating.start().toMap()).containsEntry("dateTime", "2026-10-01T10:00:00").doesNotContainKey("timeZone");

        ICalEvent allDay = single("BEGIN:VEVENT", "UID:d", "DTSTART;VALUE=DATE:20261003", "DTEND;VALUE=DATE:20261004", "END:VEVENT");
        assertThat(allDay.start().allDay()).isTrue();
        assertThat(allDay.toMap()).containsEntry("allDay", true);
        assertThat(allDay.start().toMap()).containsEntry("date", "2026-10-03");
    }

    @Test
    void tzid_quotedPrefixedAndUnknown() {
        ICalEvent quoted = single("BEGIN:VEVENT", "UID:q", "DTSTART;TZID=\"Europe/Berlin\":20261001T100000", "END:VEVENT");
        assertThat(quoted.start().zone()).isEqualTo(ZoneId.of("Europe/Berlin"));

        ICalEvent prefixed = single("BEGIN:VEVENT", "UID:p",
                "DTSTART;TZID=/mozilla.org/20050126_1/Europe/Berlin:20261001T100000", "END:VEVENT");
        assertThat(prefixed.start().zone()).isEqualTo(ZoneId.of("Europe/Berlin"));

        ICalEvent windows = single("BEGIN:VEVENT", "UID:w", "DTSTART;TZID=W. Europe Standard Time:20261001T100000", "END:VEVENT");
        assertThat(windows.start().zone()).isNull();
        assertThat(windows.start().toMap()).containsEntry("timeZone", "W. Europe Standard Time")
                .containsEntry("dateTime", "2026-10-01T10:00:00");
    }

    @Test
    void end_fromDurationOrOneDayForAllDay() {
        ICalEvent duration = single("BEGIN:VEVENT", "UID:1", "DTSTART:20261001T100000Z", "DURATION:PT1H30M", "END:VEVENT");
        assertThat(duration.end().local()).isEqualTo(LocalDateTime.of(2026, 10, 1, 11, 30));

        ICalEvent days = single("BEGIN:VEVENT", "UID:2", "DTSTART:20261001T100000Z", "DURATION:P1DT2H", "END:VEVENT");
        assertThat(days.end().local()).isEqualTo(LocalDateTime.of(2026, 10, 2, 12, 0));

        ICalEvent weeks = single("BEGIN:VEVENT", "UID:3", "DTSTART;VALUE=DATE:20261001", "DURATION:P2W", "END:VEVENT");
        assertThat(weeks.end().date()).isEqualTo(LocalDate.of(2026, 10, 15));

        ICalEvent allDay = single("BEGIN:VEVENT", "UID:4", "DTSTART;VALUE=DATE:20261003", "END:VEVENT");
        assertThat(allDay.end().date()).isEqualTo(LocalDate.of(2026, 10, 4));

        ICalEvent timedNoEnd = single("BEGIN:VEVENT", "UID:5", "DTSTART:20261001T100000Z", "END:VEVENT");
        assertThat(timedNoEnd.end()).isNull();

        ICalEvent brokenDuration = single("BEGIN:VEVENT", "UID:6", "DTSTART:20261001T100000Z", "DURATION:bogus", "END:VEVENT");
        assertThat(brokenDuration.end()).isNull();
    }

    @Test
    void rruleAndRecurrenceId_arePassedThrough() {
        List<ICalEvent> events = ICalParser.parseEvents(ics(
                "BEGIN:VEVENT", "UID:s", "DTSTART:20261006T070000Z", "RRULE:FREQ=WEEKLY;BYDAY=TU;COUNT=4", "END:VEVENT",
                "BEGIN:VEVENT", "UID:s", "RECURRENCE-ID:20261013T070000Z", "DTSTART:20261013T090000Z", "END:VEVENT"));
        assertThat(events.get(0).rrule()).isEqualTo("FREQ=WEEKLY;BYDAY=TU;COUNT=4");
        assertThat(events.get(0).recurring()).isTrue();
        assertThat(events.get(1).recurrenceId()).isEqualTo("20261013T070000Z");
        assertThat(events.get(1).recurring()).isFalse();
    }

    @Test
    void alarmProperties_doNotLeakIntoTheEvent() {
        ICalEvent e = single("BEGIN:VEVENT", "UID:1", "DTSTART:20261001T100000Z",
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Erinnerung", "TRIGGER:-PT15M", "END:VALARM",
                "SUMMARY:Nach dem Alarm", "END:VEVENT");
        assertThat(e.description()).isNull();
        assertThat(e.summary()).isEqualTo("Nach dem Alarm");
    }

    @Test
    void quotedParameterValues_mayContainColonsAndSemicolons() {
        ICalEvent e = single("BEGIN:VEVENT", "UID:1", "DTSTART:20261001T100000Z",
                "LOCATION;ALTREP=\"http://example.org/a;b:c\":Raum 1", "END:VEVENT");
        assertThat(e.location()).isEqualTo("Raum 1");
    }

    @Test
    void missingFields_areNullAndLeftOutOfTheMap() {
        ICalEvent e = single("BEGIN:VEVENT", "UID:1", "END:VEVENT");
        assertThat(e.start()).isNull();
        assertThat(e.toMap()).containsOnlyKeys("uid", "allDay");
    }

    @Test
    void brokenInput_isSkippedNotFatal() {
        List<ICalEvent> events = ICalParser.parseEvents(ics(
                "BEGIN:VEVENT", "UID:1", "this line has no separator", ":novalue", "DTSTART:2026-garbage",
                "SUMMARY:Rest bleibt", "END:VEVENT"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).start()).isNull();
        assertThat(events.get(0).summary()).isEqualTo("Rest bleibt");

        assertThat(ICalParser.parseEvents(null)).isEmpty();
        assertThat(ICalParser.parseEvents("")).isEmpty();
        assertThat(ICalParser.parseEvents("<html>not a calendar</html>")).isEmpty();
    }

    @Test
    void unterminatedEvent_isClosedByTheNextOne() {
        List<ICalEvent> events = ICalParser.parseEvents(ics(
                "BEGIN:VEVENT", "UID:a", "DTSTART:20261001T100000Z", "BEGIN:VALARM",
                "BEGIN:VEVENT", "UID:b", "DTSTART:20261002T100000Z", "END:VEVENT"));
        assertThat(events).extracting(ICalEvent::uid).containsExactly("a", "b");
    }

    @Test
    void firstOccurrenceOfAPropertyWins() {
        ICalEvent e = single("BEGIN:VEVENT", "UID:1", "SUMMARY:Erster", "SUMMARY:Zweiter", "END:VEVENT");
        assertThat(e.summary()).isEqualTo("Erster");
    }

    @Test
    void calendarName_isReadFromXWrCalname() {
        ICalParser.ICalCalendar calendar = ICalParser.parse(ics("X-WR-CALNAME:Feiertage\\, DE",
                "BEGIN:VEVENT", "UID:1", "X-WR-CALNAME:nicht dieser", "END:VEVENT"));
        assertThat(calendar.name()).isEqualTo("Feiertage, DE");
    }

    @Test
    void lowercaseNamesAndLfEndings_areAccepted() {
        List<ICalEvent> events = ICalParser.parseEvents("begin:vcalendar\nbegin:vevent\nuid:1\ndtstart:20261001T100000z\n"
                + "summary:klein\nend:vevent\nend:vcalendar\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).start().toInstant(ZoneOffset.UTC)).hasToString("2026-10-01T10:00:00Z");
        assertThat(events.get(0).summary()).isEqualTo("klein");
    }
}
