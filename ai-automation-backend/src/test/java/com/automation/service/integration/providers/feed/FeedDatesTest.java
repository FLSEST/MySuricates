package com.automation.service.integration.providers.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FeedDatesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // RFC 822 / 1123 in its lenient forms
            "Tue, 06 Oct 2026 08:30:00 GMT|2026-10-06T08:30:00Z",
            "Tue, 06 Oct 2026 10:30:00 +0200|2026-10-06T08:30:00Z",
            "6 Oct 2026 10:30 +02:00|2026-10-06T08:30:00Z",
            "Tuesday, 06 October 2026 08:30:00 UT|2026-10-06T08:30:00Z",
            "06 Oct 26 08:30:00 GMT|2026-10-06T08:30:00Z",
            "06 Oct 99 08:30:00 GMT|1999-10-06T08:30:00Z",
            "Tue, 06 Oct 2026 08:30:00 EST|2026-10-06T13:30:00Z",
            "Tue, 06 Oct 2026 08:30:00 MESZ|2026-10-06T06:30:00Z",
            "Tue, 06 Oct 2026 08:30:00 XYZ|2026-10-06T08:30:00Z",
            "Tue, 06 Oct 2026 08:30:00|2026-10-06T08:30:00Z",
            // ISO 8601
            "2026-10-06T08:30:00Z|2026-10-06T08:30:00Z",
            "2026-10-06T10:30:00+02:00|2026-10-06T08:30:00Z",
            "2026-10-06T10:30:00+0200|2026-10-06T08:30:00Z",
            "2026-10-06 10:30:00 +0200|2026-10-06T08:30:00Z",
            "2026-10-06T08:30:00|2026-10-06T08:30:00Z",
            "2026-10-06|2026-10-06T00:00:00Z"
    })
    void parse_supportedFormats(String input, String expected) {
        assertThat(FeedDates.parse(input)).isEqualTo(Instant.parse(expected));
    }

    @Test
    void parse_trimsAndCollapsesWhitespace() {
        assertThat(FeedDates.parse("  Tue,   06 Oct 2026\n08:30:00   GMT ")).isEqualTo(Instant.parse("2026-10-06T08:30:00Z"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "gestern", "Tue, 06 Foo 2026 08:30:00 GMT", "Tue, 31 Feb 2026 08:30:00 GMT",
            "06 Oct 226 08:30:00 GMT", "Tue, 06 Oct 2026 25:30:00 GMT", "2026-13-40"})
    void parse_unparseableYieldsNull(String input) {
        assertThat(FeedDates.parse(input)).isNull();
    }

    @Test
    void parse_overlongInputYieldsNull() {
        assertThat(FeedDates.parse("2026-10-06" + " ".repeat(5) + "x".repeat(100))).isNull();
    }
}
