package com.automation.service.integration;

/**
 * How far a provider has been checked against the real service.
 *
 * <p>{@link #SIMULATED} is the default for every provider: its tests run against recorded or
 * hand-written responses (a local {@code HttpServer}), so the real API may still disagree. A provider
 * becomes {@link #VERIFIED} only by an entry in {@link VerifiedProviders} after a run against the real
 * service — a local container of the real software counts. The UI shows a notice for SIMULATED providers.
 */
public enum IntegrationVerification {
    /** Checked against the real service. */
    VERIFIED,
    /** Only checked against simulated responses. */
    SIMULATED
}
