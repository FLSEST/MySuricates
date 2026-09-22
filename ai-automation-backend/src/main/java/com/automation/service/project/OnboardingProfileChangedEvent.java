package com.automation.service.project;

/**
 * An onboarding profile's text changed (edited, reset, updated by the seeder, deleted): every
 * project whose file includes it is written again (TODO 644fd5ad). {@code base} — the base profile,
 * which every project includes.
 */
public record OnboardingProfileChangedEvent(String profileId, boolean base) {}
