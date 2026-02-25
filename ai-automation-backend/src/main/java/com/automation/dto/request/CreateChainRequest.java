package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateChainRequest(
        @NotBlank String name,
        String description,
        String icon,
        @NotEmpty List<ChainEntryRequest> entries,
        Boolean stopOnFailure
) {}
