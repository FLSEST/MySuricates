package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Anlegen eines Workflows aus dem Konvertierungs-Vorschlag eines Laufs.
 *
 * <p>{@code blocks} und {@code description} sind der Vorschlag aus der Vorschau, den der Nutzer
 * gesehen hat (TODO 935af6d3): Mitgegeben werden genau diese gespeichert, ohne den Vorschlag neu zu
 * erzeugen; {@code name} ist dann Pflicht. Ohne {@code blocks} erzeugt der Server den Vorschlag neu
 * (alte Clients).
 */
public record ConvertRunToWorkflowRequest(
        @NotBlank String projectId,
        String name,
        String description,
        List<BlockDefinition> blocks
) {
    /** Nur Projekt und Name — der Vorschlag wird serverseitig neu erzeugt. */
    public ConvertRunToWorkflowRequest(String projectId, String name) {
        this(projectId, name, null, null);
    }
}
