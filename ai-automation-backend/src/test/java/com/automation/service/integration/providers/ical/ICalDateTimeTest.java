package com.automation.service.integration.providers.ical;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ICalDateTimeTest {

    @Test
    void fromIso_dateIsAllDay() {
        ICalDateTime d = ICalDateTime.fromIso("2026-10-03", "Europe/Berlin");
        assertThat(d.allDay()).isTrue();
        assertThat(d.toICalValue()).isEqualTo("20261003");
        assertThat(d.propertyName("DTSTART")).isEqualTo("DTSTART;VALUE=DATE");
    }

    @Test
    void fromIso_offsetAndZuluBecomeUtc() {
        assertThat(ICalDateTime.fromIso("2026-10-01T10:00:00+02:00", null).toICalValue()).isEqualTo("20261001T080000Z");
        ICalDateTime z = ICalDateTime.fromIso("2026-10-01T08:00:00Z", "Europe/Berlin");
        assertThat(z.utc()).isTrue();
        assertThat(z.propertyName("DTSTART")).isEqualTo("DTSTART");
    }

    @Test
    void fromIso_localTimeUsesTheZone() {
        ICalDateTime t = ICalDateTime.fromIso("2026-10-01T10:00", "Europe/Berlin");
        assertThat(t.propertyName("DTSTART")).isEqualTo("DTSTART;TZID=Europe/Berlin");
        assertThat(t.toICalValue()).isEqualTo("20261001T100000");
        assertThat(t.toInstant(ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
    }

    @Test
    void fromIso_localTimeWithUtcOrNoZoneIsUtc() {
        assertThat(ICalDateTime.fromIso("2026-10-01T10:00:00", "UTC").utc()).isTrue();
        assertThat(ICalDateTime.fromIso("2026-10-01T10:00:00", null).utc()).isTrue();
    }

    @Test
    void fromIso_rejectsGarbageAndUnknownZones() {
        assertThatThrownBy(() -> ICalDateTime.fromIso("morgen", "UTC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalDateTime.fromIso("2026-10-01T10:00", "Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalDateTime.fromIso(" ", "UTC")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsMalformedValues() {
        assertThatThrownBy(() -> ICalDateTime.parse("2026-10-01", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalDateTime.parse("", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalDateTime.parse("20261301T100000Z", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_valueDateWinsOverShape() {
        assertThat(ICalDateTime.parse("20261003", null, "DATE").date()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(ICalDateTime.parse("20261003", null, null).allDay()).isTrue();
    }

    @Test
    void toInstant_placesAllDayAndFloatingInTheFallbackZone() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        assertThat(ICalDateTime.parse("20261003", null, "DATE").toInstant(berlin)).isEqualTo(Instant.parse("2026-10-02T22:00:00Z"));
        assertThat(ICalDateTime.parse("20261003T120000", null, null).toInstant(berlin)).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
        assertThat(ICalDateTime.parse("20261003T120000Z", null, null).toInstant(berlin)).isEqualTo(Instant.parse("2026-10-03T12:00:00Z"));
    }

    @Test
    void toMap_winterTimeCarriesTheWinterOffset() {
        assertThat(ICalDateTime.parse("20261201T100000", "Europe/Berlin", null).toMap())
                .containsEntry("dateTime", "2026-12-01T10:00:00+01:00");
    }
}
