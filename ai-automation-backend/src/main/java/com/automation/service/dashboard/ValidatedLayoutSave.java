package com.automation.service.dashboard;

/** A save request that passed DashboardLayoutValidator; content is canonical JSON. */
public record ValidatedLayoutSave(
        String deviceLabel,
        int formatVersion,
        String baseVersionId,
        boolean force,
        String canonicalContent,
        String contentHash,
        int viewCount) {
}
