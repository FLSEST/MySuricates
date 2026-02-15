package com.automation.dto.response;

import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;

import java.time.Instant;
import java.util.Map;

public record ProjectResponse(
        String id,
        String name,
        ProjectType type,
        String path,
        Map<String, Object> config,
        ProjectStatus status,
        ContainerResponse container,
        Instant createdAt,
        Instant updatedAt
) {}
