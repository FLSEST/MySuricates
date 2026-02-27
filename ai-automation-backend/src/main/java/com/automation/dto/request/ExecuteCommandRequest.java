package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ExecuteCommandRequest(
        @NotBlank String command
) {}
