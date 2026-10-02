package com.automation.executor.compress;

/** Output of one stage plus a short note for the "Stages:" line (null = nothing to report). */
public record StageResult(String output, String note) {
}
