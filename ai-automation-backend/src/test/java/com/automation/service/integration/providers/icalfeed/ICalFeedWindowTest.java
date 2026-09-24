package com.automation.service.integration.providers.icalfeed;

import com.automation.service.integration.providers.ical.ICalDateTime;
import com.automation.service.integration.providers.ical.ICalEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ICalFeedWindowTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");

    private static ICalEvent event(String start, String end, String rrule) {
        return new ICalEvent("u", "s", null, null, null, ICalDateTime.parse(start, null, null),
                end == null ? null : ICalDateTime.parse(end, null, null), rrule, null);
    }

    @Test
    void overlaps_startsInsideEndsInsideOrSpans() {
        assertThat(ICalFeedWindow.overlaps(event("20261005T100000Z", "20261005T110000Z", null), FROM, TO, ZoneOffset.UTC)).isTrue();
        assertThat(ICalFeedWindow.overlaps(event("20260930T230000Z", "20261001T010000Z", null), FROM, TO, ZoneOffset.UTC)).isTrue();
        assertThat(ICalFeedWindow.overlaps(event("20260901T000000Z", "20261201T000000Z", null), FROM, TO, ZoneOffset.UTC)).isTrue();
        // end is exclusive: an event ending exactly at 'from' is outside, one starting at 'to' too
        assertThat(ICalFeedWindow.overlaps(event("20260930T220000Z", "20261001T000000Z", null), FROM, TO, ZoneOffset.UTC)).isFalse();
        assertThat(ICalFeedWindow.overlaps(event("20261101T000000Z", "20261101T010000Z", null), FROM, TO, ZoneOffset.UTC)).isFalse();
        // zero-length (no end): inside when start is within [from, to)
        assertThat(ICalFeedWindow.overlaps(event("20261001T000000Z", null, null), FROM, TO, ZoneOffset.UTC)).isTrue();
        assertThat(ICalFeedWindow.overlaps(event("20260930T235959Z", null, null), FROM, TO, ZoneOffset.UTC)).isFalse();
    }

    @Test
    void allDayEvents_useTheGivenZone() {
        ICalEvent lastDayOfSeptember = new ICalEvent("u", "s", null, null, null,
                ICalDateTime.parse("20260930", null, "DATE"), ICalDateTime.parse("20261001", null, "DATE"), null, null);
        Instant berlinFrom = Instant.parse("2026-09-30T22:00:00Z");
        assertThat(ICalFeedWindow.overlaps(lastDayOfSeptember, berlinFrom, TO, ZoneId.of("Europe/Berlin"))).isFalse();
        assertThat(ICalFeedWindow.overlaps(lastDayOfSeptember, berlinFrom, TO, ZoneId.of("America/New_York"))).isTrue();
    }

    @Test
    void series_includedWhileRunning_excludedWhenOverOrNotStarted() {
        assertThat(ICalFeedWindow.seriesTouches(event("20250101T100000Z", null, "FREQ=WEEKLY"), FROM, TO, ZoneOffset.UTC)).isTrue();
        assertThat(ICalFeedWindow.seriesTouches(event("20250101T100000Z", null, "FREQ=WEEKLY;UNTIL=20260930T000000Z"),
                FROM, TO, ZoneOffset.UTC)).isFalse();
        assertThat(ICalFeedWindow.seriesTouches(event("20250101T100000Z", null, "FREQ=WEEKLY;UNTIL=20261001"),
                FROM, TO, ZoneOffset.UTC)).isTrue();
        assertThat(ICalFeedWindow.seriesTouches(event("20261201T100000Z", null, "FREQ=DAILY"), FROM, TO, ZoneOffset.UTC)).isFalse();
        // an unreadable UNTIL counts as open-ended rather than hiding the series
        assertThat(ICalFeedWindow.seriesTouches(event("20250101T100000Z", null, "FREQ=DAILY;UNTIL=garbage"), FROM, TO, ZoneOffset.UTC)).isTrue();
    }

    @Test
    void select_dropsEventsWithoutStartAndMarksSeries() {
        ICalEvent noStart = new ICalEvent("x", "s", null, null, null, null, null, null, null);
        List<?> out = ICalFeedWindow.select(List.of(noStart, event("20250101T100000Z", null, "FREQ=YEARLY"),
                event("20261002T100000Z", null, null)), FROM, TO, ZoneOffset.UTC);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).toString()).contains("recurring=true");
    }
}
