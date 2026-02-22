package com.automation.dto.request;

import com.automation.entity.ProjectType;

import java.util.Map;

public record UpdateProjectRequest(
        String name,
        String subtitle,
        String description,
        ProjectType type,
        String path,
        Map<String, Object> config
) {}
