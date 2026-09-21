package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @param locale {@code de}/{@code en}: language of names, labels and — for bilingual templates — the
 *               steps; without it German (the behaviour before the field existed)
 */
public record InstallChainTemplateRequest(
        @NotBlank String projectId,
        String chainName,
        String locale
) {
    public InstallChainTemplateRequest(String projectId, String chainName) {
        this(projectId, chainName, null);
    }
}
