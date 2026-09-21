package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @param locale {@code de}/{@code en}: language of name, description and — for bilingual pool files —
 *               the steps; without it English
 */
public record InstallPoolWorkflowRequest(
        @NotBlank String projectId,
        String name,
        Boolean forceCreate,
        String locale
) {
    /** Call sites written before {@code locale} existed install in English. */
    public InstallPoolWorkflowRequest(String projectId, String name, Boolean forceCreate) {
        this(projectId, name, forceCreate, null);
    }
}
