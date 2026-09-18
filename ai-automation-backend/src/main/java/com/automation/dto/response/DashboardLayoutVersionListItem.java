package com.automation.dto.response;

import java.time.Instant;

/**
 * Closed projection for the version list: never loads the layout JSON (pool-size=1, blobs stay in
 * the DB).
 *
 * <p>Lives in the response DTO package although Spring Data fills it: it travels from the
 * repository through the service up into the controller, and a projection in
 * {@code com.automation.repository} would make every such signature a controller-to-repository
 * dependency (see {@code LayerRuleTest}).
 */
public interface DashboardLayoutVersionListItem {
    String getId();

    Instant getCreatedAt();

    String getDeviceLabel();

    String getName();

    int getFormatVersion();

    int getViewCount();

    String getContentHash();
}
