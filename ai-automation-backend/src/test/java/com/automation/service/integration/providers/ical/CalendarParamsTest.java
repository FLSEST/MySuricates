package com.automation.service.integration.providers.ical;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarParamsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant DEFAULT = Instant.parse("2000-01-01T00:00:00Z");

    @Test
    void instant_readsDatesInTheZone_andTreatsAnEndDateAsInclusive() {
        assertThat(CalendarParams.instant(Map.of("from", "2026-10-01"), "from", DEFAULT, false, BERLIN))
                .isEqualTo(Instant.parse("2026-09-30T22:00:00Z"));
        assertThat(CalendarParams.instant(Map.of("to", "2026-10-01"), "to", DEFAULT, true, ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
    }

    @Test
    void instant_offsetsWinOverTheZone_localTimesUseIt() {
        assertThat(CalendarParams.instant(Map.of("k", "2026-10-01T10:00:00+02:00"), "k", DEFAULT, false, ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(CalendarParams.instant(Map.of("k", "2026-10-01T10:00:00Z"), "k", DEFAULT, false, BERLIN))
                .isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(CalendarParams.instant(Map.of("k", "2026-10-01T10:00"), "k", DEFAULT, false, BERLIN))
                .isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
    }

    @Test
    void instant_defaultsAndErrors() {
        assertThat(CalendarParams.instant(Map.of(), "k", DEFAULT, false, BERLIN)).isEqualTo(DEFAULT);
        assertThat(CalendarParams.instant(Map.of("k", " "), "k", DEFAULT, false, BERLIN)).isEqualTo(DEFAULT);
        assertThatThrownBy(() -> CalendarParams.instant(Map.of("k", "gestern"), "k", DEFAULT, false, BERLIN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'k'");
    }

    @Test
    void zone_andScalarParams() {
        assertThat(CalendarParams.zone(Map.of(), "tz", BERLIN)).isEqualTo(BERLIN);
        assertThat(CalendarParams.zone(Map.of("tz", "UTC"), "tz", BERLIN)).isEqualTo(ZoneId.of("UTC"));
        assertThatThrownBy(() -> CalendarParams.zone(Map.of("tz", "Nowhere/City"), "tz", BERLIN))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(CalendarParams.paramInt(Map.of("n", "7"), "n", 1)).isEqualTo(7);
        assertThat(CalendarParams.paramInt(Map.of("n", 9), "n", 1)).isEqualTo(9);
        assertThat(CalendarParams.paramInt(Map.of(), "n", 1)).isEqualTo(1);
        assertThatThrownBy(() -> CalendarParams.paramInt(Map.of("n", "x"), "n", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarParams.requireParam(Map.of("u", ""), "u"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("u");
    }

    @Test
    void readBounded_rejectsStreamsOverTheLimit() throws Exception {
        assertThat(CalendarParams.readBounded(new ByteArrayInputStream(new byte[10]), 10, "x")).hasSize(10);
        assertThatThrownBy(() -> CalendarParams.readBounded(new ByteArrayInputStream(new byte[11]), 10, "Feed"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Feed exceeds the size limit");
    }

    /** ac639b39: a JSON number is read like its text — 1.5 is not cut to 1, nothing wraps around. */
    @Test
    void paramInt_readsANumberLikeItsText() {
        assertThat(CalendarParams.paramInt(Map.of("limit", " 7 "), "limit", 1)).isEqualTo(7);
        for (Object bad : new Object[] {1.5, 5.0, 3_000_000_000L}) {
            assertThatThrownBy(() -> CalendarParams.paramInt(Map.of("limit", bad), "limit", 1))
                    .as(String.valueOf(bad))
                    .hasMessage("Parameter 'limit' must be a number");
        }
    }
}
