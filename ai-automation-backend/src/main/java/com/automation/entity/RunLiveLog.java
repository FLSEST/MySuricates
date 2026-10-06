package com.automation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The live log of a finished run, kept after its in-memory buffer is gone. {@link #content} is the
 * gzip-compressed JSON written by {@code RunLiveLogCodec}; the row goes with its run (FK cascade).
 */
@Entity
@Table(name = "run_live_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunLiveLog {

    @Id
    @Column(name = "run_id")
    private String runId;

    /** Buffer the lines came from; a new id tells clients to reset their de-duplication. */
    @Column(name = "buffer_id", nullable = false)
    private String bufferId;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    /** Lines that had already left the ring before the run ended. */
    @Column(nullable = false)
    private long skipped;

    @Column(nullable = false, columnDefinition = "BLOB")
    private byte[] content;

    @Column(name = "stored_at", nullable = false)
    private Instant storedAt;
}
