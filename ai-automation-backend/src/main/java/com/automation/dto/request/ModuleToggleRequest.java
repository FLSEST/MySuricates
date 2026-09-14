package com.automation.dto.request;

import jakarta.validation.constraints.NotNull;

public record ModuleToggleRequest(@NotNull Boolean enabled) {}
