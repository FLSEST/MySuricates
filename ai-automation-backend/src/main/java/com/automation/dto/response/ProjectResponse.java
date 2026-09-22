package com.automation.dto.response;

import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;

import java.time.Instant;
import java.util.Map;

public record ProjectResponse(
        String id,
        String name,
        String subtitle,
        String description,
        ProjectType type,
        String path,
        Map<String, Object> config,
        String aiContext,
        ProjectStatus status,
        ContainerResponse container,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long workflowCount
) {}
