package com.automation.dto.request;

import com.automation.entity.ProjectType;

import java.util.Map;

public record UpdateProjectRequest(
        String name,
        ProjectType type,
        String path,
        Map<String, Object> config
) {}
