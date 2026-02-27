package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ApproveExecutionRequest(
        @NotBlank String answer
) {}
