package com.automation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** TODO-IDs fuer {@code POST /goals/{id}/todos} (zuordnen) und {@code PUT /goals/{id}/order} (Reihenfolge). */
public record GoalTodoIdsRequest(@NotNull List<String> todoIds) {}
