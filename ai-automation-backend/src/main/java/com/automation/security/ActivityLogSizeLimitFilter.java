package com.automation.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Caps POST /api/v1/activity-log — activity logs are short text, 64 KiB is generous. */
@Component
public class ActivityLogSizeLimitFilter extends RequestBodySizeLimitFilter {

    private static final String TARGET_PATH = "/api/v1/activity-log";

    @Value("${automation.activity-log.max-request-bytes:65536}")
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
