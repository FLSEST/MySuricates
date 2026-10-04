package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Creates a workflow from a template on the server, with the blueprints its sub-workflow blocks call
 * (TODO 093d2f11).
 *
 * @param name        name of the new workflow; blank → the template's name
 * @param description blank → the template's description
 * @param locale      {@code de}/{@code en}: language of names and steps; without it English
 * @param metadata    metadata of the new workflow, as on {@code POST /workflows}
 */
public record InstallTemplateRequest(
        @NotBlank String projectId,
        String name,
        String description,
        String locale,
        Map<String, Object> metadata
) {
}
