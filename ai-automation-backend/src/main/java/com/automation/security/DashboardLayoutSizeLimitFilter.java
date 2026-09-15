package com.automation.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Caps POST /api/v1/dashboard/layouts at 256 KiB — typical layouts are a few KiB; a maximal one (20 views × 100 configured widgets) comes close to the limit. */
@Component
public class DashboardLayoutSizeLimitFilter extends RequestBodySizeLimitFilter {

    private static final String TARGET_PATH = "/api/v1/dashboard/layouts";

    @Value("${automation.dashboard.layout.max-request-bytes:262144}")
    private long maxBytes;

    @Override
    protected String targetPath() {
        return TARGET_PATH;
    }

    @Override
    protected long maxBytes() {
        return maxBytes;
    }
}
