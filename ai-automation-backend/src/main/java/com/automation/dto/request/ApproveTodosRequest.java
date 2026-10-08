package com.automation.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Decision on the TODOs a run proposed. {@code approved} is required: a missing field used to bind as
 * {@code false} and discarded every proposal for good (TODO 8697722b); now it is a 400.
 */
public record ApproveTodosRequest(@NotNull Boolean approved) {}
