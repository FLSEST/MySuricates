package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Live output of running blocks (shell lines, AI tool calls) streamed into the run's WebSocket log
 * and kept in memory for replay after a page reload; when the run ends, RunLiveLogArchive stores the
 * buffer if {@link #persistDefault} or the workflow's {@code liveLogPersist} says so.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.live-log")
@Getter
@Setter
public class LiveLogConfig {
    /** Master switch; off = no streaming, no buffers, the replay endpoint reports "not available". */
    private boolean enabled = true;
    /** Longer lines are cut (characters). */
    private int maxLineLength = 2000;
    /** Lines kept per run for replay; older ones are dropped. */
    private int bufferLines = 1000;
    /** Lines are batched into one WebSocket message at most this often. */
    private long flushIntervalMs = 250;
    /** Upper bound of lines per batch; beyond it the newest lines are sent and the rest counted as skipped. */
    private int maxLinesPerFlush = 200;
    /** Upper bound of characters per batch (keeps a WebSocket session below its send-buffer limit). */
    private int maxCharsPerFlush = 32_000;
    /** A finished run's buffer stays available for replay this long. */
    private int retentionMinutes = 10;
    /** Hard cap on buffered runs; the oldest finished buffer is evicted first. */
    private int maxBufferedRuns = 20;
    /** Keep a finished run's live log in the database unless the workflow says otherwise (runtime-changeable). */
    private boolean persistDefault = true;
}
