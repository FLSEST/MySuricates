package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ConvertRunToWorkflowRequest(
        @NotBlank String projectId,
        String name
) {}
