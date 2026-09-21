package com.automation.entity;

import com.automation.util.LocalDateStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricDailyTest {

    @Test
    void builderCarriesEveryMeasuredValue() {
        Instant created = Instant.parse("2026-09-21T21:55:00Z");
        RequestMetricDaily row = RequestMetricDaily.builder()
                .day(LocalDate.of(2026, 9, 21)).route("GET /api/v1/todos")
                .calls(120).p50Ms(12).p95Ms(80).maxMs(900).p95WaitMs(30).waitShare(0.25)
                .maxSqlStatements(7).avgSqlStatements(3.5).maxResponseBytes(40_000).avgResponseBytes(8_000)
                .createdAt(created)
                .build();

        assertThat(row.getId()).isNull();
        assertThat(row.getDay()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(row.getRoute()).isEqualTo("GET /api/v1/todos");
        assertThat(row.getCalls()).isEqualTo(120);
        assertThat(row.getP50Ms()).isEqualTo(12);
        assertThat(row.getP95Ms()).isEqualTo(80);
        assertThat(row.getMaxMs()).isEqualTo(900);
        assertThat(row.getP95WaitMs()).isEqualTo(30);
        assertThat(row.getWaitShare()).isEqualTo(0.25);
        assertThat(row.getMaxSqlStatements()).isEqualTo(7);
        assertThat(row.getAvgSqlStatements()).isEqualTo(3.5);
        assertThat(row.getMaxResponseBytes()).isEqualTo(40_000);
        assertThat(row.getAvgResponseBytes()).isEqualTo(8_000);
        assertThat(row.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void newRowStartsWithZeroCosts() {
        RequestMetricDaily row = new RequestMetricDaily();

        assertThat(row.getCalls()).isZero();
        assertThat(row.getP95Ms()).isZero();
        assertThat(row.getWaitShare()).isZero();
    }

    /**
     * SQLite cannot read a mapped LocalDate back, so the day is stored as ISO text — which also
     * keeps the baseline range queries chronological.
     */
    @Test
    void dayIsStoredAsIsoTextThatSortsChronologically() throws Exception {
        Convert convert = RequestMetricDaily.class.getDeclaredField("day").getAnnotation(Convert.class);
        assertThat(convert.converter()).isEqualTo(LocalDateStringConverter.class);

        LocalDateStringConverter converter = new LocalDateStringConverter();
        assertThat(converter.convertToDatabaseColumn(LocalDate.of(2026, 9, 1))).isEqualTo("2026-09-01");
        assertThat(converter.convertToDatabaseColumn(LocalDate.of(2026, 9, 1)))
                .isLessThan(converter.convertToDatabaseColumn(LocalDate.of(2026, 10, 1)));
        assertThat(converter.convertToEntityAttribute("2026-09-01")).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void mapsToTheDailyTableWithSnakeCaseColumns() throws Exception {
        assertThat(RequestMetricDaily.class.getAnnotation(Table.class).name()).isEqualTo("request_metrics_daily");
        assertThat(RequestMetricDaily.class.getDeclaredField("p95WaitMs").getAnnotation(Column.class).name())
                .isEqualTo("p95_wait_ms");
        assertThat(RequestMetricDaily.class.getDeclaredField("route").getAnnotation(Column.class).nullable())
                .isFalse();
    }
}
