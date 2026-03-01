package com.automation.dto.response;

public record TodoCountResponse(
        long open,
        long inProgress,
        long done,
        long backlog
) {}
