package com.automation.dto.projection;

/**
 * Die Run-Zaehler oben im Dashboard aus EINER Abfrage (SUM/CASE) statt aus fuenf einzelnen
 * {@code count}-Aufrufen (TODO 2ccb9e40). {@code succeeded} zaehlt COMPLETED und
 * COMPLETED_WITH_ERRORS, {@code startedSince} die Runs ab dem uebergebenen Zeitpunkt (Tagesbeginn).
 */
public record RunOverviewCounts(long total, long succeeded, long failed, long startedSince) {}
