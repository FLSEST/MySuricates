package com.automation.service.integration.providers.ical;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ICalPatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private static final String STORED = String.join("\r\n",
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Nextcloud//EN",
            "BEGIN:VEVENT", "UID:u1", "DTSTAMP:20260101T000000Z", "SEQUENCE:4",
            "DTSTART:20261001T080000Z", "DURATION:PT1H", "SUMMARY:Alt",
            "ATTENDEE;CN=Anna:mailto:anna@example.org", "X-NC-FOO:bleibt",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Erinnerung", "TRIGGER:-PT15M", "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:u1", "RECURRENCE-ID:20261008T080000Z", "DTSTART:20261008T090000Z", "SUMMARY:Ausnahme",
            "END:VEVENT", "END:VCALENDAR", "");

    @Test
    void patch_replacesOnlyTheGivenPropertiesOfTheMaster() {
        String out = ICalPatcher.patch(STORED, Set.of("SUMMARY"), List.of("SUMMARY:Neu"), Set.of(), NOW);

        assertThat(out).contains("SUMMARY:Neu", "ATTENDEE;CN=Anna:mailto:anna@example.org", "X-NC-FOO:bleibt",
                "BEGIN:VALARM", "DESCRIPTION:Erinnerung", "DURATION:PT1H", "SUMMARY:Ausnahme", "SEQUENCE:5",
                "DTSTAMP:20260924T120000Z", "LAST-MODIFIED:20260924T120000Z");
        assertThat(out).doesNotContain("SUMMARY:Alt", "SEQUENCE:4", "DTSTAMP:20260101T000000Z");
        // properties stay in front of the VALARM, as RFC 5545 requires
        assertThat(out.indexOf("SUMMARY:Neu")).isLessThan(out.indexOf("BEGIN:VALARM"));

        List<ICalEvent> events = ICalParser.parseEvents(out);
        assertThat(events).extracting(ICalEvent::summary).containsExactly("Neu", "Ausnahme");
    }

    @Test
    void patch_newEndDropsDurationAndAddsMissingTimezone() {
        String out = ICalPatcher.patch(STORED, Set.of("DTSTART", "DTEND"),
                List.of("DTSTART;TZID=Europe/Berlin:20261001T100000", "DTEND;TZID=Europe/Berlin:20261001T120000"),
                Set.of(ZoneId.of("Europe/Berlin")), NOW);

        assertThat(out).doesNotContain("DURATION:PT1H").contains("BEGIN:VTIMEZONE", "TZID:Europe/Berlin");
        assertThat(out.indexOf("BEGIN:VTIMEZONE")).isLessThan(out.indexOf("BEGIN:VEVENT"));
        ICalEvent master = ICalParser.parseEvents(out).get(0);
        assertThat(master.end().toMap()).containsEntry("dateTime", "2026-10-01T12:00:00+02:00");
    }

    @Test
    void patch_doesNotDuplicateAnExistingTimezone() {
        String withTz = STORED.replaceFirst("BEGIN:VEVENT", "BEGIN:VTIMEZONE\r\nTZID:Europe/Berlin\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT");
        String out = ICalPatcher.patch(withTz, Set.of("DTSTART"), List.of("DTSTART;TZID=Europe/Berlin:20261001T100000"),
                Set.of(ZoneId.of("Europe/Berlin")), NOW);
        assertThat(out.split("BEGIN:VTIMEZONE", -1)).hasSize(2);
    }

    @Test
    void patch_removingWithoutReplacementDeletesTheProperty() {
        String out = ICalPatcher.patch(STORED.replace("SUMMARY:Alt", "SUMMARY:Alt\r\nLOCATION:Raum"),
                Set.of("LOCATION"), List.of(), Set.of(), NOW);
        assertThat(out).doesNotContain("LOCATION:Raum");
    }

    @Test
    void patch_withoutSequenceStartsAtOne_andKeepsFoldedLinesIntact() {
        String plain = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDESCRIPTION:lang\r\n er Text\r\nSUMMARY:a\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        String out = ICalPatcher.patch(plain, Set.of("SUMMARY"), List.of("SUMMARY:b"), Set.of(), NOW);
        assertThat(out).contains("SEQUENCE:1", "DESCRIPTION:langer Text");
    }

    @Test
    void findMaster_fallsBackToTheFirstInstanceWhenNoMasterExists() {
        List<String> lines = ICalParser.unfold("BEGIN:VCALENDAR\nBEGIN:VEVENT\nRECURRENCE-ID:1\nEND:VEVENT\nEND:VCALENDAR\n");
        assertThat(ICalPatcher.findMaster(lines)).containsExactly(1, 3);
    }

    @Test
    void patch_rejectsObjectsWithoutEvent() {
        assertThatThrownBy(() -> ICalPatcher.patch("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", Set.of("SUMMARY"),
                List.of("SUMMARY:x"), Set.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
