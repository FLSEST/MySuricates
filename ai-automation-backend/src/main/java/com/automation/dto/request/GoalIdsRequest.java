package com.automation.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Eine Reihenfolge von IDs (TODO c60bf914): Projekte eines Ziels ({@code PUT /goals/{id}/projects/order})
 * oder Teilziele eines Projekts im Ziel ({@code PUT /goals/{id}/milestones/order}).
 */
public record GoalIdsRequest(@NotEmpty List<String> ids) {}
