package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record InstallPoolWorkflowRequest(
        @NotBlank String projectId,
        String name
) {}
