package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record InstallChainTemplateRequest(
        @NotBlank String projectId,
        String chainName
) {}
