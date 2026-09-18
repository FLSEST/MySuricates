package com.automation.entity;

import com.automation.util.LocalDateStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What one route cost on one day — the durable end of the request measurement.
 *
 * <p>The live numbers live in memory ({@code RequestMetricsCollector}); this table is the memory
 * that survives a restart and lets "slower than it used to be" be a statement about data rather
 * than a feeling. One row per day and route.
 */
@Entity
@Table(name = "request_metrics_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RequestMetricDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    @Convert(converter = LocalDateStringConverter.class)
    private LocalDate day;

    /** Route template with method, e.g. {@code GET /api/v1/todos}. */
    @Column(nullable = false)
    private String route;

    @Column(nullable = false)
    private long calls;

    @Column(name = "p50_ms", nullable = false)
    private long p50Ms;

    @Column(name = "p95_ms", nullable = false)
    private long p95Ms;

    @Column(name = "max_ms", nullable = false)
    private long maxMs;

    /** 95th percentile of the time spent waiting for the single pooled connection. */
    @Column(name = "p95_wait_ms", nullable = false)
    private long p95WaitMs;

    /** Share of total time spent waiting rather than working, 0..1. */
    @Column(name = "wait_share", nullable = false)
    private double waitShare;

    @Column(name = "max_sql_statements", nullable = false)
    private int maxSqlStatements;

    @Column(name = "avg_sql_statements", nullable = false)
    private double avgSqlStatements;

    @Column(name = "max_response_bytes", nullable = false)
    private long maxResponseBytes;

    @Column(name = "avg_response_bytes", nullable = false)
    private long avgResponseBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
