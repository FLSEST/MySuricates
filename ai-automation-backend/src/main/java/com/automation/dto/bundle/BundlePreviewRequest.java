package com.automation.dto.bundle;

/**
 * {@code POST /api/v1/projects/import/preview}.
 *
 * @param targetProjectId optional: an existing project the bundle would go into — then
 *                        {@code nameTakenInTarget} is filled; without it every workflow reports {@code false}
 */
public record BundlePreviewRequest(ProjectBundle bundle, String targetProjectId) {}
