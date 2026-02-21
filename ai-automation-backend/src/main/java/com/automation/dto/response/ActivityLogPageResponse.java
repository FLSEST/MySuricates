package com.automation.dto.response;

import java.util.List;

public record ActivityLogPageResponse(
        List<ActivityLogResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
