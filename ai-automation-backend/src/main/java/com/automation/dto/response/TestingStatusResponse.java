package com.automation.dto.response;

public record TestingStatusResponse(
        boolean containerRunning,
        boolean healthy,
        String testVersion,
        String testBuildTimestamp
) {
}
