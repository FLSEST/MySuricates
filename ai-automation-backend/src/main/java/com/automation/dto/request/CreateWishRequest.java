package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateWishRequest(
        @NotBlank String text,
        String projectId,
        Integer maxIterations,
        List<String> referenceWorkflowIds
) {}
