package com.automation.repository;

import java.time.Instant;

/** Closed projection for the version list: never loads the layout JSON (pool-size=1, blobs stay in the DB). */
public interface DashboardLayoutVersionListItem {
    String getId();

    Instant getCreatedAt();

    String getDeviceLabel();

    String getName();

    int getFormatVersion();

    int getViewCount();

    String getContentHash();
}
