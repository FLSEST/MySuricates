package com.automation.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ActivateSkillsRequest(
        @NotEmpty List<String> skillIds
) {}
