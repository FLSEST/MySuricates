package com.automation.dto.request;

import com.automation.entity.ProjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateProjectRequest(
        @NotBlank String name,
        String subtitle,
        String description,
        @NotNull ProjectType type,
        @NotBlank String path,
        Map<String, Object> config,
        String aiContext
) {}
